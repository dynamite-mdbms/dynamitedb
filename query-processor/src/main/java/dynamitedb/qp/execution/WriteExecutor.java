package dynamitedb.qp.execution;

import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.transaction.TransactionCoordinator;

/** Menjalankan INSERT, UPDATE, DELETE, dan CREATE INDEX (QP-10, QP-11, QP-13). */
public class WriteExecutor {

  private final WriteStore store;
  private final WriteCoordinator writes;
  private final PlanTranslator translator;
  private final ExpressionEvaluator evaluator;
  private final TransactionCoordinator coordinator;
  private final IndexAdmin indexAdmin;
  private final DeleteExecutor delete;

  public WriteExecutor(
      WriteStore store,
      WriteCoordinator writes,
      PlanTranslator translator,
      ExpressionEvaluator evaluator,
      TransactionCoordinator coordinator,
      IndexAdmin indexAdmin,
      DeleteExecutor delete) {
    this.store = store;
    this.writes = writes;
    this.translator = translator;
    this.evaluator = evaluator;
    this.coordinator = coordinator;
    this.indexAdmin = indexAdmin;
    this.delete = delete;
  }

  // TODO: authorizeTable(WRITE) -> store.prepareInsert -> writes.execute
  public long insert(InsertPlan plan, TransactionId tx) {
    return 1;
  }

  // TODO: kumpulkan dulu semua baris dari plan.source(), baru prepareUpdate satu per satu
  public long update(UpdatePlan plan, TransactionId tx) {
    return 0;
  }

  public long delete(DeletePlan plan, TransactionId tx) {
    return delete.execute(plan, tx);
  }

  // tx dari transaksi implisit QueryProcessorImpl; CREATE INDEX di dalam BEGIN ditolak
  // ExecutionEngine
  // TODO: authorizeTable(WRITE) -> indexAdmin.createIndex
  public void createIndex(CreateIndexPlan plan, TransactionId tx) {}
}
