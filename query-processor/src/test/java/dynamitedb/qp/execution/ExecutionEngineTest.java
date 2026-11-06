package dynamitedb.qp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.FakeComponents;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.session.SessionContext;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExecutionEngineTest {

  private static final TransactionId TX = new TransactionId(5);
  private static final Schema EMPTY = new Schema(List.of());
  private static final PlanNode SCAN = new TableScanPlan("employee", "e");
  private static final ColumnRef ID = new ColumnRef(Optional.empty(), "id");

  private final List<String> calls = new ArrayList<>();
  private final SessionContext session = new SessionContext(new SessionId(1), "client");
  private final ExecutionEngine engine = engine();

  @Test
  void sendsTransactionCommandsToTransactionExecutor() {
    QueryResult result = run(new TransactionCommandPlan(TransactionCommand.BEGIN));

    assertEquals("BEGIN", result.message());
    assertEquals(List.of("transaction BEGIN"), calls);
  }

  @Test
  void sendsWritesToWriteExecutorWithAffectedRows() {
    session.setActiveTx(Optional.of(TX));
    LiteralExpression one = new LiteralExpression(new IntValue(1));

    QueryResult insert = run(new InsertPlan("employee", new Row(List.of())));
    run(new UpdatePlan(SCAN, "employee", ID, one));
    run(new DeletePlan(SCAN, "employee"));

    assertEquals(2, insert.affectedRows());
    assertEquals("2 row(s) affected", insert.message());
    assertTrue(insert.schema().columns().isEmpty());
    assertEquals(List.of("insert", "update", "delete"), calls);
  }

  @Test
  void sendsCreateIndexToWriteExecutor() {
    session.setActiveTx(Optional.of(TX));

    QueryResult result = run(new CreateIndexPlan("employee_id", "employee", "id", IndexKind.HASH));

    assertEquals("Index employee_id created", result.message());
    assertEquals(List.of("createIndex"), calls);
  }

  @Test
  void readsRelationalPlansThroughTranslator() {
    session.setActiveTx(Optional.of(TX));
    Schema output = new Schema(List.of(new Column("e", "id", DataType.INTEGER, 0, false)));

    QueryResult result = engine.execute(new PreparedQuery(SCAN, output), session);

    assertSame(output, result.schema());
    assertEquals(List.of("translate", "read"), calls);
  }

  @Test
  void statementsNeedActiveTransaction() {
    DbmsException error =
        assertThrows(DbmsException.class, () -> run(new DeletePlan(SCAN, "employee")));

    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertTrue(calls.isEmpty());
  }

  private QueryResult run(PlanNode root) {
    return engine.execute(new PreparedQuery(root, EMPTY), session);
  }

  // semua executor diganti versi palsu yang cuma nyatet dipanggil apa
  private ExecutionEngine engine() {
    FakeComponents fake = new FakeComponents();
    TransactionCoordinator coordinator = new TransactionCoordinator(fake, fake, fake);
    ExpressionEvaluator evaluator = new ExpressionEvaluator();
    WriteCoordinator writes = new WriteCoordinator(fake, fake);

    PlanTranslator translator =
        new PlanTranslator(evaluator, null, fake, coordinator) {
          @Override
          public Operator translate(PlanNode plan, TransactionId tx) {
            calls.add("translate");
            return new EmptyOperator();
          }
        };
    ReadExecutor reader =
        new ReadExecutor() {
          @Override
          public QueryResult execute(Operator root, Schema outputSchema) {
            calls.add("read");
            return new QueryResult(outputSchema, List.of(), 0, "");
          }
        };
    DeleteExecutor delete = new DeleteExecutor(fake, writes, translator, evaluator, coordinator);
    WriteExecutor writer =
        new WriteExecutor(fake, writes, translator, evaluator, coordinator, fake, delete) {
          @Override
          public long insert(InsertPlan plan, TransactionId tx) {
            calls.add("insert");
            return 2;
          }

          @Override
          public long update(UpdatePlan plan, TransactionId tx) {
            calls.add("update");
            return 2;
          }

          @Override
          public long delete(DeletePlan plan, TransactionId tx) {
            calls.add("delete");
            return 2;
          }

          @Override
          public void createIndex(CreateIndexPlan plan, TransactionId tx) {
            calls.add("createIndex");
          }
        };
    TransactionCommandExecutor transactions =
        new TransactionCommandExecutor(coordinator) {
          @Override
          public QueryResult execute(TransactionCommand cmd, SessionContext session) {
            calls.add("transaction " + cmd);
            return new QueryResult(EMPTY, List.of(), 0, cmd.name());
          }
        };
    return new ExecutionEngine(translator, reader, writer, transactions);
  }

  private static final class EmptyOperator implements Operator {
    @Override
    public void open() {}

    @Override
    public Optional<OperatorRow> next() {
      return Optional.empty();
    }

    @Override
    public void close() {}
  }
}
