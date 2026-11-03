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
import java.util.ArrayList;
import java.util.List;
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
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(tx, "tx");

    String table = plan.table();
    coordinator.authorizeTable(tx, table, AccessMode.WRITE);

    Operator sourceOperator = translator.translate(plan.source(), tx);
    List<RecordId> targetRids = new ArrayList<>();

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
        targetRids.add(rid);
      }
    } finally {
      sourceOperator.close();
    }

    long affectedRows = 0;

    for (RecordId rid : targetRids) {
      PreparedWrite prepared = store.prepareDelete(tx, rid);
      writes.execute(prepared);
      affectedRows++;
    }

    return affectedRows;
  }
}
