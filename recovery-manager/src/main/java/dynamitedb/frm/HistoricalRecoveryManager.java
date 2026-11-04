package dynamitedb.frm;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.HistoricalRecoveryService;
import dynamitedb.contracts.api.HistoricalRecoveryStorage;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;

/** Configurable Milestone 1 mock; no replay or publication is performed. */
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
  public RecoveryResult recoverTo(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoricalRecoveryManager.recoverTo");
    return result;
  }

  public RecoveryBoundary resolveBoundary(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoricalRecoveryManager.resolveBoundary");
    return boundary;
  }
}
