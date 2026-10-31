package dynamitedb.qp.execution;

import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.Objects;

public class DeleteExecutor {
  private final WriteStore store;
  private final WriteCoordinator writes;
  private final PlanTranslator translator;
  private final ExpressionEvaluator evaluator;
  private final TransactionCoordinator coordinator;

  public DeleteExecutor(
      WriteStore store,
      WriteCoordinator writes,
      PlanTranslator translator,
      ExpressionEvaluator evaluator,
      TransactionCoordinator coordinator) {
    this.store = Objects.requireNonNull(store, "store");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.translator = Objects.requireNonNull(translator, "translator");
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  public long execute(DeletePlan plan, TransactionId tx) {
    return 1L;
  }
}
