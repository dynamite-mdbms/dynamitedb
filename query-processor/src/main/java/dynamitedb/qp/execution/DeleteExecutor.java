package dynamitedb.qp.execution;

import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    return execute(plan, tx, null);
  }

  long execute(DeletePlan plan, TransactionId tx, KeyConstraints keys) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(tx, "tx");

    String table = plan.table();
    KeyConstraints.Statement constraints = null;
    if (keys == null) {
      coordinator.authorizeTable(tx, table, AccessMode.WRITE);
    } else {
      constraints = keys.delete(table, tx);
    }

    Operator sourceOperator = translator.translate(plan.source(), tx);
    Map<RecordId, KeyConstraints.Change> targets = new LinkedHashMap<>();

    try {
      sourceOperator.open();
      while (true) {
        Optional<OperatorRow> opRow = sourceOperator.next();
        if (opRow.isEmpty()) {
          break;
        }

        Map<String, RecordId> origins = opRow.get().origins();
        RecordId rid = opRow.get().origins().get(table);
        if (rid == null && origins.size() == 1) {
          rid = origins.values().iterator().next();
        }

        if (rid == null) {
          throw new DbmsException(
              ErrorCode.INVALID_STATE,
              "Target RecordId not found in provenance for table: " + table);
        }
        targets.putIfAbsent(rid, new KeyConstraints.Change(opRow.get().row(), null));
      }
    } finally {
      sourceOperator.close();
    }

    if (constraints != null) constraints.validateChanges(targets);
    for (RecordId rid : targets.keySet()) {
      PreparedWrite prepared = store.prepareDelete(tx, rid);
      writes.execute(prepared);
    }

    return targets.size();
  }
}
