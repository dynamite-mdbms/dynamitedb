package dynamitedb.frm;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.HistoricalRecoveryService;
import dynamitedb.contracts.api.HistoricalRecoveryStorage;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Performs point-in-time recovery into an isolated storage generation. */
public final class HistoricalRecoveryManager implements HistoricalRecoveryService {
  private final RecoveryResult result;
  private final RecoveryBoundary boundary;
  private final MockRecoveryContext context;
  private final HistoricalRecoveryStorage storage;
  private final HistoryCatalog catalog;
  private final StorageGenerationAdmin generations;
  private final LogManager log;
  private final WalService wal;
  private final GenerationManifestStore manifests;
  private final RecoveryCoordinator maintenance;

  public HistoricalRecoveryManager(RecoveryResult result, RecoveryBoundary boundary) {
    this(result, boundary, new MockRecoveryContext());
  }

  public HistoricalRecoveryManager(MockRecoveryContext context) {
    this(context.result(), context.boundary(), context);
  }

  public HistoricalRecoveryManager(
      RecoveryResult result, RecoveryBoundary boundary, MockRecoveryContext context) {
    this(result, boundary, context, null, null, null, null, null, null, null);
  }

  public HistoricalRecoveryManager(
      HistoricalRecoveryStorage storage,
      HistoryCatalog catalog,
      StorageGenerationAdmin generations,
      LogManager log,
      WalService wal,
      GenerationManifestStore manifests,
      RecoveryCoordinator maintenance) {
    this(
        storage, catalog, generations, log, wal, manifests, maintenance, new MockRecoveryContext());
  }

  public HistoricalRecoveryManager(
      HistoricalRecoveryStorage storage,
      HistoryCatalog catalog,
      StorageGenerationAdmin generations,
      LogManager log,
      WalService wal,
      GenerationManifestStore manifests,
      RecoveryCoordinator maintenance,
      MockRecoveryContext context) {
    this(
        context.result(),
        context.boundary(),
        context,
        Objects.requireNonNull(storage, "storage"),
        Objects.requireNonNull(catalog, "catalog"),
        Objects.requireNonNull(generations, "generations"),
        Objects.requireNonNull(log, "log"),
        Objects.requireNonNull(wal, "wal"),
        Objects.requireNonNull(manifests, "manifests"),
        Objects.requireNonNull(maintenance, "maintenance"));
  }

  private HistoricalRecoveryManager(
      RecoveryResult result,
      RecoveryBoundary boundary,
      MockRecoveryContext context,
      HistoricalRecoveryStorage storage,
      HistoryCatalog catalog,
      StorageGenerationAdmin generations,
      LogManager log,
      WalService wal,
      GenerationManifestStore manifests,
      RecoveryCoordinator maintenance) {
    this.result = Objects.requireNonNull(result, "result");
    this.boundary = Objects.requireNonNull(boundary, "boundary");
    this.context = Objects.requireNonNull(context, "context");
    this.storage = storage;
    this.catalog = catalog;
    this.generations = generations;
    this.log = log;
    this.wal = wal;
    this.manifests = manifests;
    this.maintenance = maintenance;
  }

  @Override
  @SuppressWarnings("try")
  public RecoveryResult recoverTo(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoricalRecoveryManager.recoverTo");
    if (storage == null) {
      return result;
    }

    try (MaintenanceGuard ignored = maintenance.acquireMaintenance()) {
      HistoryCatalog.RecoveryPlan plan = catalog.plan(target);
      RecoveryBoundary targetBoundary = plan.boundary();
      RecoveryHighWater highWater = log.readHighWater();
      if (targetBoundary.inclusiveLsn().value() > highWater.lsn().value()) {
        throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Target exceeds retained WAL history");
      }

      UUID generationId = UUID.randomUUID();
      UUID newLineage = UUID.randomUUID();
      RecoveryStage stage = storage.createStage(plan.snapshot(), generationId);
      for (ChangeLogRecord record : plan.changes()) {
        storage.replayInStage(
            stage, new RecoveryMutation(record.change.rid(), record.change.after()), record.lsn);
      }
      storage.rebuildStageIndexes(stage);
      SealedGeneration sealed = storage.sealStage(stage, targetBoundary);
      if (!sealed.id().equals(stage.id()) || !sealed.boundary().equals(targetBoundary)) {
        throw new DbmsException(
            ErrorCode.RECOVERY_FAILURE,
            "Storage sealed a generation with an unexpected identity or boundary");
      }
      wal.prepareLineage(newLineage, sealed, highWater);
      catalog.registerSnapshot(
          new SnapshotMetadata(
              sealed.id(),
              newLineage,
              highWater.lsn(),
              targetBoundary.commitTime().orElseGet(Instant::now)));
      GenerationManifest published = catalog.buildManifest(sealed, newLineage, highWater);
      manifests.publishAtomically(published);
      generations.switchToPublishedGeneration(published);
      wal.activate(published);
      maintenance.initializeRecoveredState(highWater);
      return new RecoveryResult(RecoveryStatus.SUCCESS, highWater, "Historical recovery completed");
    }
  }

  public RecoveryBoundary resolveBoundary(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoricalRecoveryManager.resolveBoundary");
    return catalog == null ? boundary : catalog.resolveBoundary(target);
  }
}
