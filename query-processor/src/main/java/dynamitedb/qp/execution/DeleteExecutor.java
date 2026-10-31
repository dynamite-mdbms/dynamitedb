package dynamitedb.qp.execution;

import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.Objects;

public class DeleteExecutor {

  public DeleteExecutor(
      WriteStore store,
      WriteCoordinator writes,
      PlanTranslator translator,
      ExpressionEvaluator evaluator,
      TransactionCoordinator coordinator) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(writes, "writes");
    Objects.requireNonNull(translator, "translator");
    Objects.requireNonNull(evaluator, "evaluator");
    Objects.requireNonNull(coordinator, "coordinator");
  }

  public long execute(DeletePlan plan, TransactionId tx) {
    return 1L;
  }
}
