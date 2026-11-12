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
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
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
import dynamitedb.frm.LogFile;
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
import dynamitedb.sm.catalog.SampleDatabaseLoader;
import dynamitedb.sm.catalog.StorageCatalogAdapter;
import dynamitedb.sm.facade.StorageManagerImpl;
import dynamitedb.sm.facade.StorageRecoveryAdapter;
import dynamitedb.sm.generation.GenerationStore;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PersistentRuntime implements AutoCloseable {
  private static final TransactionId ZERO_TX = new TransactionId(0);
  private static final LogSequenceNumber ZERO_LSN = new LogSequenceNumber(0);

  private final LogManager log;
  private final DbmsBootstrap bootstrap;

  public PersistentRuntime(DbmsConfig config) {
    Objects.requireNonNull(config, "config");
    Path data = config.dataDirectory().toAbsolutePath().normalize();
    Path manifestPath = data.resolve("active-generation.properties");
    boolean fresh = Files.notExists(manifestPath);
    UUID initialId = identity("active:", data);
    UUID initialLineage = identity("lineage:", data);
    RecoveryHighWater zero = new RecoveryHighWater(ZERO_LSN, ZERO_TX, 0, Optional.empty());
    GenerationManifest initial = new GenerationManifest(initialId, initialLineage, ZERO_LSN, zero);
    var manifests = new AtomicGenerationManifestStore(data, initial);
    GenerationManifest active = manifests.readActive();
    Path walPath = LogFile.path(data, active.walLineage());
    if (fresh && Files.exists(walPath)) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "WAL exists without an active generation manifest");
    }
    if (!fresh && Files.notExists(walPath)) {
      throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Active generation manifest has no WAL");
    }
    log =
        fresh
            ? LogManager.createNew(data, active.walLineage(), active.highWater())
            : LogManager.openExisting(data, active);

    var wal = new WalService(new CommitClock(), log);
    var disk = new DiskManager(data);
    var buffer = new BufferPool(disk, wal);
    SnapshotMetadata baseSnapshot =
        new SnapshotMetadata(initialId, initialLineage, ZERO_LSN, Instant.EPOCH);
    var catalog =
        new CatalogManager(
            data, null, FreshRecoveryMetadata.predicate(manifestPath, walPath, baseSnapshot));
    var catalogAccess = new StorageCatalogAdapter(catalog);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(data, buffer, serializer, catalog);
    var indexes = new IndexManager(heap, serializer);
    var records = new RecordStore(heap, buffer, serializer, indexes, catalog);
    var generations = new GenerationStore(data, manifests, buffer, catalog, indexes, records);
    var storage = new StorageManagerImpl(records, catalog, indexes);
    var storageRecovery = new StorageRecoveryAdapter(records, generations, indexes, buffer);
    var undo = new UndoManager(log, storageRecovery);

    var registry = new TransactionRegistry();
    var maintenance = new MaintenanceController(registry);
    var bridge = new TransactionRecoveryBridge(wal, undo);
    var strictProtocol = new Strict2PLProtocol(new LockManager());
    var timestampProtocol =
        new TimestampOrderingProtocol(
            new TimestampRegistry(), new StrictWriteIntentTable(), registry);
    var concurrency =
        new ConcurrencyManagerImpl(
            registry,
            config.protocol() == ProtocolType.STRICT_2PL ? strictProtocol : timestampProtocol,
            bridge,
            maintenance);
    concurrency.setProtocol(config.protocol());

    var crash =
        new CrashRecoveryManager(log, wal, manifests, storageRecovery, undo, storageRecovery);
    var checkpoint = new CheckpointManager(concurrency, storageRecovery, log);
    var history = new HistoryCatalog(manifests, log, baseSnapshot);
    var historical =
        new HistoricalRecoveryManager(
            storageRecovery, history, storageRecovery, log, wal, manifests, concurrency);
    var recovery = new RecoveryManagerImpl(wal, undo, crash, historical, checkpoint);
    var initializer = new RecoveryInitializer(manifests, recovery, concurrency);

    var cost = new CostEstimator(catalogAccess);
    var optimizer =
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
    var engine =
        new ExecutionEngine(
            translator, new ReadExecutor(), writer, new TransactionCommandExecutor(coordinator));
    var processor = new QueryProcessorImpl(optimizer, new SessionManager(), engine, coordinator);

    bootstrap =
        new DbmsBootstrap(
            processor,
            () -> {
              var guard = concurrency.acquireMaintenance();
              try {
                maintenance.beginRecovery();
                if (fresh) {
                  SnapshotMetadata seed = generations.captureSnapshot(baseSnapshot);
                  initializer.createNew(seed);
                } else {
                  initializer.openExisting();
                }
              } finally {
                guard.close();
              }
              new SampleDatabaseLoader(storage, concurrency, concurrency).loadIfAbsent(catalog);
            });
  }

  public DbmsBootstrap bootstrap() {
    return bootstrap;
  }

  @Override
  public void close() {
    bootstrap.shutdown();
    log.close();
  }

  private static UUID identity(String prefix, Path data) {
    return UUID.nameUUIDFromBytes(
        (prefix + data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
