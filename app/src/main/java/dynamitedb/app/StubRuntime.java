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
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.frm.AtomicGenerationManifestStore;
import dynamitedb.frm.CheckpointManager;
import dynamitedb.frm.CommitClock;
import dynamitedb.frm.CrashRecoveryManager;
import dynamitedb.frm.HistoricalRecoveryManager;
import dynamitedb.frm.HistoryCatalog;
import dynamitedb.frm.LogFile;
import dynamitedb.frm.LogManager;
import dynamitedb.frm.LogRecordCodec;
import dynamitedb.frm.RecoveryInitializer;
import dynamitedb.frm.RecoveryManagerImpl;
import dynamitedb.frm.UndoManager;
import dynamitedb.frm.WalService;
import dynamitedb.frm.mock.MockRecoveryContext;
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
import java.util.List;
import java.util.Objects;

/** A composition of the actual module classes with their existing fixed stub implementations. */
public final class StubRuntime {
  private final MockRecoveryContext recoveryContext;
  private final QueryOptimizerImpl optimizer;
  private final StorageManagerImpl storage;
  private final ConcurrencyManagerImpl concurrency;
  private final RecoveryManagerImpl recovery;
  private final QueryProcessorImpl processor;
  private final DbmsBootstrap bootstrap;

  public StubRuntime(DbmsConfig config) {
    Objects.requireNonNull(config, "config");
    recoveryContext = new MockRecoveryContext();
    var manifests =
        new AtomicGenerationManifestStore(
            config.dataDirectory(), recoveryContext.manifest(), recoveryContext);
    var logFile = new LogFile(recoveryContext);
    var codec = new LogRecordCodec(recoveryContext);
    var log =
        new LogManager(
            config.dataDirectory(), recoveryContext.lineage(), logFile, codec, recoveryContext);
    var wal = new WalService(new CommitClock(recoveryContext), log, recoveryContext);

    var disk = new DiskManager(config.dataDirectory());
    var buffer = new BufferPool(disk, wal);
    var catalog = new CatalogManager();
    var catalogAccess = new StorageCatalogAdapter(catalog);
    var serializer = new RecordSerializer();
    var heap = new HeapFile();
    var indexes = new IndexManager();
    var records = new RecordStore(heap, buffer, serializer, indexes);
    var generations =
        new GenerationStore(config.dataDirectory(), manifests, buffer, catalog, indexes, records);
    storage = new StorageManagerImpl(records, catalog, indexes);
    var storageRecovery = new StorageRecoveryAdapter(records, generations, indexes, buffer);
    var undo = new UndoManager(log, storageRecovery, recoveryContext);

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

    var checkpoint = new CheckpointManager(concurrency, storageRecovery, log, recoveryContext);
    var history =
        new HistoryCatalog(manifests, log, List.of(recoveryContext.snapshot()), recoveryContext);
    var historical =
        new HistoricalRecoveryManager(
            storageRecovery,
            history,
            storageRecovery,
            log,
            wal,
            manifests,
            concurrency,
            recoveryContext);
    var crash =
        new CrashRecoveryManager(
            log, wal, manifests, storageRecovery, undo, storageRecovery, recoveryContext);
    recovery = new RecoveryManagerImpl(wal, undo, crash, historical, checkpoint);
    var initializer = new RecoveryInitializer(manifests, crash, recoveryContext);

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
                    new ProjectionPushdown(),
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
    bootstrap = new DbmsBootstrap(processor, initializer::openExisting);
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

  public StorageManagerImpl storage() {
    return storage;
  }

  public ConcurrencyManagerImpl concurrency() {
    return concurrency;
  }

  public RecoveryManagerImpl recovery() {
    return recovery;
  }

  public MockRecoveryContext recoveryContext() {
    return recoveryContext;
  }
}
