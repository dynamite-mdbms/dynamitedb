package dynamitedb.qp.execution;

import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    this.store = Objects.requireNonNull(store, "store");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.translator = Objects.requireNonNull(translator, "translator");
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.indexAdmin = Objects.requireNonNull(indexAdmin, "indexAdmin");
    this.delete = Objects.requireNonNull(delete, "delete");
  }

  public long insert(InsertPlan plan, TransactionId tx) {
    coordinator.authorizeTable(tx, plan.table(), AccessMode.WRITE);
    writes.execute(store.prepareInsert(tx, plan.table(), plan.row()));
    return 1;
  }

  public long update(UpdatePlan plan, TransactionId tx) {
    // izin WRITE diminta sebelum scan supaya tidak ada upgrade lock READ -> WRITE
    coordinator.authorizeTable(tx, plan.table(), AccessMode.WRITE);
    Map<RecordId, Row> targets = collectTargets(plan, tx);
    for (Map.Entry<RecordId, Row> target : targets.entrySet()) {
      writes.execute(store.prepareUpdate(tx, target.getKey(), target.getValue()));
    }
    return targets.size();
  }

  public long delete(DeletePlan plan, TransactionId tx) {
    return delete.execute(plan, tx);
  }

  // tx dari transaksi implisit QueryProcessorImpl; CREATE INDEX di dalam BEGIN sudah ditolak QP1
  public void createIndex(CreateIndexPlan plan, TransactionId tx) {
    coordinator.authorizeTable(tx, plan.table(), AccessMode.WRITE);
    indexAdmin.createIndex(plan.index(), plan.table(), plan.column(), plan.kind());
  }

  // semua baris target dikumpulkan dulu sebelum ada yang ditulis (Halloween problem)
  private Map<RecordId, Row> collectTargets(UpdatePlan plan, TransactionId tx) {
    Map<RecordId, Row> targets = new LinkedHashMap<>();
    Operator source = translator.translate(plan.source(), tx);
    source.open();
    try {
      for (Optional<OperatorRow> next = source.next(); next.isPresent(); next = source.next()) {
        OperatorRow row = next.get();
        targets.putIfAbsent(targetRid(plan, row), updatedRow(plan, row));
      }
    } finally {
      source.close();
    }
    return targets;
  }

  private Row updatedRow(UpdatePlan plan, OperatorRow row) {
    int index = targetIndex(plan.target(), row.schema().columns());
    Column column = row.schema().columns().get(index);
    Value value = fitColumn(evaluator.evaluate(plan.value(), row), column);
    List<Value> values = new ArrayList<>(row.row().values());
    values.set(index, value);
    return new Row(values);
  }

  private RecordId targetRid(UpdatePlan plan, OperatorRow row) {
    Map<String, RecordId> origins = row.origins();
    RecordId rid;
    if (plan.target().relation().isPresent()) {
      rid = origins.get(plan.target().relation().get());
    } else if (origins.size() == 1) {
      rid = origins.values().iterator().next();
    } else {
      rid = origins.get(plan.table());
    }
    if (rid == null) {
      throw new DbmsException(
          ErrorCode.INVALID_PLAN, "UPDATE source row has no record id for " + plan.table());
    }
    return rid;
  }

  private int targetIndex(ColumnRef target, List<Column> columns) {
    int found = -1;
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      boolean sameRelation =
          target
              .relation()
              .map(relation -> relation.equalsIgnoreCase(column.relation()))
              .orElse(true);
      if (sameRelation && column.name().equalsIgnoreCase(target.column())) {
        if (found >= 0) {
          throw new DbmsException(
              ErrorCode.INVALID_PLAN, "UPDATE column " + target.column() + " is ambiguous");
        }
        found = i;
      }
    }
    if (found < 0) {
      throw new DbmsException(
          ErrorCode.INVALID_PLAN, "UPDATE column " + target.column() + " is not in the source");
    }
    return found;
  }

  // INTEGER dinaikkan ke FLOAT untuk kolom FLOAT; FLOAT ke kolom INTEGER ditolak
  private Value fitColumn(Value value, Column column) {
    // ekspresi seperti kolom + NULL baru ketahuan NULL saat dievaluasi, Binder hanya cek literal
    if (value instanceof NullValue && !column.nullable()) {
      throw new DbmsException(
          ErrorCode.SQL_BIND, "Column " + column.name() + " does not accept NULL");
    }
    if (column.dataType() == DataType.FLOAT && value instanceof IntValue integer) {
      return new FloatValue(integer.value());
    }
    if (column.dataType() == DataType.INTEGER && value instanceof FloatValue) {
      throw new DbmsException(
          ErrorCode.SQL_BIND, "Column " + column.name() + " expects an INTEGER value");
    }
    return value;
  }
}
