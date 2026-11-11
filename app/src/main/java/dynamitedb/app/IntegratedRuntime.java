package dynamitedb.app;

import dynamitedb.ccm.ConcurrencyManagerImpl;
import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.ccm.protocol.Strict2PLProtocol;
import dynamitedb.ccm.recovery.MaintenanceController;
import dynamitedb.ccm.recovery.TransactionRecoveryBridge;
import dynamitedb.ccm.timestamp.StrictWriteIntentTable;
import dynamitedb.ccm.timestamp.TimestampOrderingProtocol;
import dynamitedb.ccm.timestamp.TimestampRegistry;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.AtomicGenerationManifestStore;
import dynamitedb.frm.CheckpointManager;
import dynamitedb.frm.CommitClock;
import dynamitedb.frm.CrashRecoveryManager;
import dynamitedb.frm.HistoricalRecoveryManager;
import dynamitedb.frm.HistoryCatalog;
import dynamitedb.frm.LogManager;
import dynamitedb.frm.RecoveryInitializer;
import dynamitedb.frm.RecoveryManagerImpl;
import dynamitedb.frm.UndoManager;
import dynamitedb.frm.WalService;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.facade.QueryOptimizerImpl;
import dynamitedb.qo.join.AccessPathOptimizer;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.JoinOrderOptimizer;
import dynamitedb.qo.join.JoinRewriter;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import dynamitedb.qo.pipeline.PlanRewrite;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.plan.PlanVisualizer;
import dynamitedb.qo.pushdown.ProjectionPushdown;
import dynamitedb.qo.pushdown.SelectionPushdown;
import dynamitedb.qp.QueryProcessorImpl;
import dynamitedb.qp.execution.DeleteExecutor;
import dynamitedb.qp.execution.ExecutionEngine;
import dynamitedb.qp.execution.PlanTranslator;
import dynamitedb.qp.execution.ReadExecutor;
import dynamitedb.qp.execution.TransactionCommandExecutor;
import dynamitedb.qp.execution.WriteCoordinator;
import dynamitedb.qp.execution.WriteExecutor;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.session.SessionManager;
import dynamitedb.qp.transaction.TransactionCoordinator;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.catalog.StorageCatalogAdapter;
import dynamitedb.sm.facade.StorageManagerImpl;
import dynamitedb.sm.facade.StorageRecoveryAdapter;
import dynamitedb.sm.generation.GenerationStore;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Runtime with persistent FRM WAL and live SM/CCM recovery ports. */
public final class IntegratedRuntime {
  private final QueryOptimizerImpl optimizer;
  private final StorageManagerImpl storage;
  private final StorageRecoveryAdapter storageRecovery;
  private final CatalogManager catalog;
  private final ConcurrencyManagerImpl concurrency;
  private final RecoveryManagerImpl recovery;
  private final QueryProcessorImpl processor;
  private final DbmsBootstrap bootstrap;
  private final RecoveryInitializer initializer;
  private final SnapshotMetadata initialSnapshot;
  private final boolean fresh;
  private final LogManager log;

  public IntegratedRuntime(DbmsConfig config) {
    Objects.requireNonNull(config, "config");
    var root = config.dataDirectory().toAbsolutePath().normalize();
    fresh = !AtomicGenerationManifestStore.exists(root);
    if (fresh && containsFiles(root)) {
      throw new IllegalStateException(
          "Database files exist without an active recovery manifest: " + root);
    }

    var zero =
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());
    var initialLineage = UUID.randomUUID();
    var initialManifest =
        new GenerationManifest(UUID.randomUUID(), initialLineage, new LogSequenceNumber(0), zero);
    var manifests = new AtomicGenerationManifestStore(root, initialManifest);
    GenerationManifest active = fresh ? initialManifest : manifests.readActive();
    if (fresh) {
      log = LogManager.createNew(root, active.walLineage(), zero);
    } else {
      long floor = LogManager.readWalFloor(root, active.walLineage());
      RecoveryHighWater seed =
          floor > 0 || active.highWater().lsn().value() == floor
              ? new RecoveryHighWater(
                  new LogSequenceNumber(floor),
                  active.highWater().maxTx(),
                  active.highWater().maxTimestamp(),
                  active.highWater().maxCommitTime())
              : zero;
      log = LogManager.openExisting(root, active.walLineage(), seed);
    }
    var wal = new WalService(new CommitClock(), log);

    var disk = new DiskManager(root);
    var buffer = new BufferPool(disk, wal);
    catalog = new CatalogManager(root);
    var catalogAccess = new StorageCatalogAdapter(catalog);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(root, buffer, serializer, catalog);
    var indexes = new IndexManager(heap, serializer);
    var records = new RecordStore(heap, buffer, serializer, indexes, catalog);
    var generations = new GenerationStore(root, manifests, buffer, catalog, indexes, records);
    storage = new StorageManagerImpl(records, catalog, indexes);
    storageRecovery = new StorageRecoveryAdapter(records, generations, indexes, buffer);

    var undo = new UndoManager(log, storageRecovery);
    var registry = new TransactionRegistry();
    var maintenance = new MaintenanceController(registry);
    var bridge = new TransactionRecoveryBridge(wal, undo);
    var strictProtocol = new Strict2PLProtocol(new LockManager());
    var timestampProtocol =
        new TimestampOrderingProtocol(
            new TimestampRegistry(), new StrictWriteIntentTable(), registry);
    concurrency =
        new ConcurrencyManagerImpl(
            registry,
            config.protocol() == ProtocolType.STRICT_2PL ? strictProtocol : timestampProtocol,
            bridge,
            maintenance);
    concurrency.setProtocol(config.protocol());

    var checkpoint = new CheckpointManager(concurrency, storageRecovery, log);
    if (fresh) {
      initialSnapshot =
          generations.captureSnapshot(
              new RecoveryBoundary(new LogSequenceNumber(0), Optional.empty()));
      var history = new HistoryCatalog(manifests, log, List.of(initialSnapshot));
      var historical =
          new HistoricalRecoveryManager(
              storageRecovery, history, storageRecovery, log, wal, manifests, concurrency);
      var crash =
          new CrashRecoveryManager(log, wal, manifests, storageRecovery, undo, storageRecovery);
      recovery = new RecoveryManagerImpl(wal, undo, crash, historical, checkpoint);
      initializer = new RecoveryInitializer(manifests, crash, concurrency);
    } else {
      initialSnapshot = null;
      var history = new HistoryCatalog(manifests, log);
      var historical =
          new HistoricalRecoveryManager(
              storageRecovery, history, storageRecovery, log, wal, manifests, concurrency);
      var crash =
          new CrashRecoveryManager(log, wal, manifests, storageRecovery, undo, storageRecovery);
      recovery = new RecoveryManagerImpl(wal, undo, crash, historical, checkpoint);
      initializer = new RecoveryInitializer(manifests, crash, concurrency);
    }

    var cost = new CostEstimator(catalogAccess);
    optimizer =
        new QueryOptimizerImpl(
            new Lexer(),
            new SqlParser(new StatementParser(), new ExpressionParser()),
            new Binder(catalogAccess),
            new PlanBuilder(),
            new OptimizerPipeline(
                List.<PlanRewrite>of(
                    new SelectionPushdown(),
                    new ProjectionPushdown(catalogAccess),
                    new JoinRewriter(),
                    new AccessPathOptimizer(catalogAccess)),
                new JoinOrderOptimizer(),
                new BestPlanSelector(cost)),
            cost,
            new PlanVisualizer());
    var coordinator = new TransactionCoordinator(concurrency, concurrency, catalogAccess);
    var evaluator = new ExpressionEvaluator();
    var translator = new PlanTranslator(evaluator, storage, catalogAccess, coordinator);
    var writes = new WriteCoordinator(storage, concurrency);
    var delete = new DeleteExecutor(storage, writes, translator, evaluator, coordinator);
    var writer =
        new WriteExecutor(storage, writes, translator, evaluator, coordinator, storage, delete);
    var commands = new TransactionCommandExecutor(coordinator);
    var engine = new ExecutionEngine(translator, new ReadExecutor(), writer, commands);
    processor = new QueryProcessorImpl(optimizer, new SessionManager(), engine, coordinator);
    bootstrap =
        new DbmsBootstrap(
            processor,
            () -> {
              if (fresh) initializer.createNew(initialSnapshot);
              else initializer.openExisting();
              storageRecovery.rebuildIndexes();
            },
            log::close);
  }

  public QueryProcessorImpl processor() {
    return processor;
  }

  public DbmsBootstrap bootstrap() {
    return bootstrap;
  }

  public QueryOptimizerImpl optimizer() {
    return optimizer;
  }

  public CatalogManager catalog() {
    return catalog;
  }

  public StorageRecoveryAdapter storageRecovery() {
    return storageRecovery;
  }

  public StorageManagerImpl storage() {
    return storage;
  }

  public ConcurrencyManagerImpl concurrency() {
    return concurrency;
  }

  public RecoveryManagerImpl recovery() {
    return recovery;
  }

  private static boolean containsFiles(java.nio.file.Path root) {
    if (!Files.exists(root)) return false;
    try (var entries = Files.list(root)) {
      return entries.findAny().isPresent();
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot inspect database directory: " + root, failure);
    }
  }
}
