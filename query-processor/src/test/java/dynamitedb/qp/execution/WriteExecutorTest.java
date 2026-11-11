package dynamitedb.qp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.FakeComponents;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WriteExecutorTest {

  private static final TransactionId TX = new TransactionId(5);
  private static final Schema SCHEMA =
      new Schema(
          List.of(
              new Column("t", "id", DataType.INTEGER, 0, false),
              new Column("t", "salary", DataType.FLOAT, 0, true)));

  private final FakeComponents fake = new FakeComponents();
  private final TransactionCoordinator coordinator = new TransactionCoordinator(fake, fake, fake);
  private final WriteCoordinator writes = new WriteCoordinator(fake, fake);

  // kalau diisi, evaluator mengembalikan nilai ini (mis. hasil ekspresi yang NULL)
  private Value forcedValue;

  // salary baru = id * 100, supaya hasil INTEGER harus dinaikkan ke FLOAT
  private final ExpressionEvaluator evaluator =
      new ExpressionEvaluator() {
        @Override
        public Value evaluate(Expression expr, OperatorRow row) {
          if (forcedValue != null) return forcedValue;
          return new IntValue(((IntValue) row.row().values().get(0)).value() * 100);
        }
      };

  @Test
  void insertAuthorizesThenLogsBeforeApply() {
    WriteExecutor executor = executor(List.of());
    Row row = new Row(List.of(new IntValue(1), new FloatValue(2.5)));

    long affected = executor.insert(new InsertPlan("t", row), TX);

    assertEquals(1, affected);
    assertEquals(List.of("access WRITE", "prepareInsert t", "log", "apply 1"), fake.events);
  }

  @Test
  void updateCollectsAllRowsBeforeWriting() {
    WriteExecutor executor = executor(List.of(sourceRow(1, 10.0), sourceRow(2, 20.0)));

    long affected = executor.update(updateSalary(), TX);

    assertEquals(2, affected);
    assertEquals(
        List.of(
            "access WRITE",
            "source open",
            "source close",
            "prepareUpdate 1 [IntValue[value=1], FloatValue[value=100.0]]",
            "log",
            "apply 1",
            "prepareUpdate 2 [IntValue[value=2], FloatValue[value=200.0]]",
            "log",
            "apply 2"),
        fake.events);
  }

  @Test
  void updateWithoutMatchingRowsWritesNothing() {
    WriteExecutor executor = executor(List.of());

    assertEquals(0, executor.update(updateSalary(), TX));
    assertTrue(fake.applied.isEmpty());
  }

  @Test
  void updateRejectsUnknownColumn() {
    WriteExecutor executor = executor(List.of(sourceRow(1, 10.0)));
    UpdatePlan plan =
        new UpdatePlan(
            new TableScanPlan("t", "t"),
            "t",
            new ColumnRef(Optional.empty(), "bonus"),
            new ColumnExpression(new ColumnRef(Optional.empty(), "id")));

    DbmsException error = assertThrows(DbmsException.class, () -> executor.update(plan, TX));
    assertEquals(ErrorCode.INVALID_PLAN, error.getCode());
    assertTrue(fake.applied.isEmpty());
  }

  @Test
  void updateRejectsNullExpressionResultForNotNullColumn() {
    forcedValue = NullValue.INSTANCE;
    WriteExecutor executor = executor(List.of(sourceRow(1, 10.0)));
    UpdatePlan plan =
        new UpdatePlan(
            new TableScanPlan("t", "t"),
            "t",
            new ColumnRef(Optional.empty(), "id"),
            new ColumnExpression(new ColumnRef(Optional.empty(), "id")));

    DbmsException error = assertThrows(DbmsException.class, () -> executor.update(plan, TX));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());
    assertEquals("Column id does not accept NULL", error.getMessage());
    assertTrue(fake.applied.isEmpty());
  }

  @Test
  void updateWritesNullExpressionResultIntoNullableColumn() {
    forcedValue = NullValue.INSTANCE;
    WriteExecutor executor = executor(List.of(sourceRow(1, 10.0)));

    assertEquals(1, executor.update(updateSalary(), TX));
    assertTrue(
        fake.events.contains("prepareUpdate 1 [IntValue[value=1], INSTANCE]"),
        fake.events.toString());
  }

  @Test
  void createIndexAuthorizesThenCallsIndexAdmin() {
    WriteExecutor executor = executor(List.of());

    executor.createIndex(new CreateIndexPlan("idx_id", "t", "id", IndexKind.B_PLUS_TREE), TX);

    assertEquals(List.of("access WRITE", "createIndex idx_id t(id) B_PLUS_TREE"), fake.events);
  }

  @Test
  void failedLogCancelsReservationWithoutApplying() {
    fake.logFailure = new DbmsException(ErrorCode.WAL_FAILURE, "log down");
    WriteExecutor executor = executor(List.of());
    Row row = new Row(List.of(new IntValue(1), new FloatValue(2.5)));

    DbmsException error =
        assertThrows(DbmsException.class, () -> executor.insert(new InsertPlan("t", row), TX));
    assertSame(fake.logFailure, error);
    assertEquals(List.of("access WRITE", "prepareInsert t", "log", "cancel"), fake.events);
  }

  private WriteExecutor executor(List<OperatorRow> sourceRows) {
    PlanTranslator translator =
        new PlanTranslator(evaluator, null, fake, coordinator) {
          @Override
          public Operator translate(PlanNode plan, TransactionId tx) {
            return new ListOperator(sourceRows, fake.events);
          }
        };
    DeleteExecutor delete = new DeleteExecutor(fake, writes, translator, evaluator, coordinator);
    return new WriteExecutor(fake, writes, translator, evaluator, coordinator, fake, delete);
  }

  private static UpdatePlan updateSalary() {
    return new UpdatePlan(
        new TableScanPlan("t", "t"),
        "t",
        new ColumnRef(Optional.empty(), "salary"),
        new ColumnExpression(new ColumnRef(Optional.empty(), "id")));
  }

  private static OperatorRow sourceRow(int id, double salary) {
    Row row = new Row(List.of(new IntValue(id), new FloatValue(salary)));
    return new OperatorRow(row, SCHEMA, Map.of("t", new RecordId(7, 1, id, 0)));
  }

  private static final class ListOperator implements Operator {
    private final List<OperatorRow> rows;
    private final List<String> events;
    private Iterator<OperatorRow> cursor;

    private ListOperator(List<OperatorRow> rows, List<String> events) {
      this.rows = rows;
      this.events = events;
    }

    @Override
    public void open() {
      events.add("source open");
      cursor = rows.iterator();
    }

    @Override
    public Optional<OperatorRow> next() {
      return cursor.hasNext() ? Optional.of(cursor.next()) : Optional.empty();
    }

    @Override
    public void close() {
      events.add("source close");
    }
  }
}
