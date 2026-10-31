package dynamitedb.frm.mock;

import dynamitedb.contracts.GenerationManifest;
import dynamitedb.contracts.GenerationManifestStore;
import dynamitedb.contracts.HistoricalRecoveryStorage;
import dynamitedb.contracts.LogSequenceNumber;
import dynamitedb.contracts.MaintenanceGuard;
import dynamitedb.contracts.RecordId;
import dynamitedb.contracts.RecordImage;
import dynamitedb.contracts.RecoveryBoundary;
import dynamitedb.contracts.RecoveryCoordinator;
import dynamitedb.contracts.RecoveryHighWater;
import dynamitedb.contracts.RecoveryMutation;
import dynamitedb.contracts.RecoveryPageStore;
import dynamitedb.contracts.RecoveryStage;
import dynamitedb.contracts.SealedGeneration;
import dynamitedb.contracts.SnapshotMetadata;
import dynamitedb.contracts.StorageGenerationAdmin;
import dynamitedb.contracts.TransactionId;
import java.util.Objects;
import java.util.UUID;

/** Standalone FRM fixtures for consumed CCM/SM ports. No other component is implemented. */
public final class MockRecoveryPorts {
  private final MockRecoveryContext context;
  private final GenerationManifestStore manifest;

  public MockRecoveryPorts(MockRecoveryContext context, GenerationManifestStore manifest) {
    this.context = Objects.requireNonNull(context, "context");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
  }

  public RecoveryCoordinator coordinator() {
    return new Coordinator();
  }

  public RecoveryPageStore pages() {
    return new Pages();
  }

  public HistoricalRecoveryStorage historicalStorage() {
    return new Historical();
  }

  public StorageGenerationAdmin generations() {
    return new Generations();
  }

  private final class Coordinator implements RecoveryCoordinator {
    @Override
    public MaintenanceGuard acquireMaintenance() {
      context.invoked("RecoveryCoordinator.acquireMaintenance");
      return () -> context.invoked("MaintenanceGuard.close");
    }

    @Override
    public long transactionTimestamp(TransactionId tx) {
      Objects.requireNonNull(tx, "tx");
      context.invoked("RecoveryCoordinator.transactionTimestamp");
      return context.highWater().maxTimestamp();
    }

    @Override
    public void initializeRecoveredState(RecoveryHighWater highWater) {
      Objects.requireNonNull(highWater, "highWater");
      context.invoked("RecoveryCoordinator.initializeRecoveredState");
    }
  }

  private final class Pages implements RecoveryPageStore {
    @Override
    public RecordImage readRecord(RecordId rid) {
      Objects.requireNonNull(rid, "rid");
      context.invoked("RecoveryPageStore.readRecord");
      return context.image();
    }

    @Override
    public void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
      Objects.requireNonNull(mutation, "mutation");
      Objects.requireNonNull(lsn, "lsn");
      context.invoked("RecoveryPageStore.applyRecovery");
    }

    @Override
    public void rebuildIndexes() {
      context.invoked("RecoveryPageStore.rebuildIndexes");
    }

    @Override
    public void flushDirtyThrough(LogSequenceNumber lsn) {
      Objects.requireNonNull(lsn, "lsn");
      context.invoked("RecoveryPageStore.flushDirtyThrough");
    }
  }

  private final class Historical implements HistoricalRecoveryStorage {
    @Override
    public SnapshotMetadata captureSnapshot(RecoveryBoundary boundary) {
      Objects.requireNonNull(boundary, "boundary");
      context.invoked("HistoricalRecoveryStorage.captureSnapshot");
      return context.snapshot();
    }

    @Override
    public RecoveryStage createStage(SnapshotMetadata snapshot, UUID id) {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(id, "id");
      context.invoked("HistoricalRecoveryStorage.createStage");
      return context.stage();
    }

    @Override
    public void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn) {
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(change, "change");
      Objects.requireNonNull(lsn, "lsn");
      context.invoked("HistoricalRecoveryStorage.replayInStage");
    }

    @Override
    public void rebuildStageIndexes(RecoveryStage stage) {
      Objects.requireNonNull(stage, "stage");
      context.invoked("HistoricalRecoveryStorage.rebuildStageIndexes");
    }

    @Override
    public SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(boundary, "boundary");
      context.invoked("HistoricalRecoveryStorage.sealStage");
      return context.sealed();
    }
  }

  private final class Generations implements StorageGenerationAdmin {
    @Override
    public GenerationManifest readActiveManifest() {
      context.invoked("StorageGenerationAdmin.readActiveManifest");
      return manifest.readActive();
    }

    @Override
    public void switchToPublishedGeneration(GenerationManifest manifest) {
      Objects.requireNonNull(manifest, "manifest");
      context.invoked("StorageGenerationAdmin.switchToPublishedGeneration");
    }
  }
}
