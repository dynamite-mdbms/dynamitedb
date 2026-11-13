package dynamitedb.qp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QueryProcessorImplTest {
  private static final Schema SCHEMA =
      new Schema(List.of(new Column("t", "id", DataType.INTEGER, 0, false)));
  private final SessionManager sessions = new SessionManager();
  private final Ports ports = new Ports();
  private final QueryProcessorImpl processor = processor();

  @Test
  void standaloneQueryUsesActualEngineAndAutoCommits() {
    SessionId session = processor.openSession("client");
    assertEquals(
        List.of(new Row(List.of(new IntValue(7)))), processor.execute(session, "SELECT").rows());
    assertTrue(sessions.get(session).activeTx().isEmpty());
    assertEquals(List.of("begin 10", "access READ", "commit 10"), ports.events);
  }

  @Test
  void explicitTransactionsRemainAttachedToTheirOwnSessions() {
    SessionId first = processor.openSession("first");
    SessionId second = processor.openSession("second");
    processor.execute(first, "BEGIN");
    processor.execute(second, "BEGIN");
    TransactionId firstTx = sessions.get(first).activeTx().orElseThrow();
    TransactionId secondTx = sessions.get(second).activeTx().orElseThrow();
    processor.execute(first, "SELECT");
    processor.execute(first, "COMMIT");
    assertEquals(TransactionStatus.COMMITTED, ports.status(firstTx));
    assertEquals(TransactionStatus.ACTIVE, ports.status(secondTx));
    assertEquals(Optional.of(secondTx), sessions.get(second).activeTx());
    processor.closeSession(second);
    assertEquals(TransactionStatus.ABORTED, ports.status(secondTx));
    assertFalse(sessions.find(second).isPresent());
  }

  @Test
  void preparationErrorAndNestedBeginPreserveTheActiveTransaction() {
    SessionId session = processor.openSession("client");
    processor.execute(session, "BEGIN");
    TransactionId tx = sessions.get(session).activeTx().orElseThrow();
    assertEquals(
        ErrorCode.SQL_SYNTAX,
        assertThrows(DbmsException.class, () -> processor.execute(session, "BAD")).getCode());
    assertEquals(
        ErrorCode.INVALID_STATE,
        assertThrows(DbmsException.class, () -> processor.execute(session, "BEGIN")).getCode());
    processor.execute(session, "SELECT");
    assertEquals(Optional.of(tx), sessions.get(session).activeTx());
    assertEquals(TransactionStatus.ACTIVE, ports.status(tx));
    processor.closeSession(session);
  }

  @Test
  void executionFailureAbortsAndAllowsTheNextQuery() {
    SessionId session = processor.openSession("client");
    ports.readFailure = true;
    assertEquals(
        ErrorCode.STORAGE_FAILURE,
        assertThrows(DbmsException.class, () -> processor.execute(session, "SELECT")).getCode());
    assertTrue(sessions.get(session).activeTx().isEmpty());
    assertEquals(TransactionStatus.ABORTED, ports.status(new TransactionId(10)));
    ports.readFailure = false;
    assertEquals(1, processor.execute(session, "SELECT").rows().size());
  }

  @Test
  void failedCommitPreservesTheOriginalFailureAndAborts() {
    SessionId session = processor.openSession("client");
    ports.commitFailure = new DbmsException(ErrorCode.WAL_FAILURE, "commit failed");
    assertEquals(
        ErrorCode.WAL_FAILURE,
        assertThrows(DbmsException.class, () -> processor.execute(session, "SELECT")).getCode());
    assertEquals(TransactionStatus.ABORTED, ports.status(new TransactionId(10)));
    assertTrue(sessions.get(session).activeTx().isEmpty());
  }

  @Test
  void failedRollbackBlocksCommandsUntilAbortSucceeds() {
    SessionId session = processor.openSession("client");
    ports.readFailure = true;
    ports.abortFailure = true;
    DbmsException original =
        assertThrows(DbmsException.class, () -> processor.execute(session, "SELECT"));
    assertEquals(ErrorCode.STORAGE_FAILURE, original.getCode());
    assertEquals(1, original.getSuppressed().length);
    assertTrue(sessions.get(session).rollbackRequired());
    ports.readFailure = false;
    int calls = ports.readCalls;
    for (String sql : List.of("SELECT", "BEGIN", "COMMIT", "INDEX")) {
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(DbmsException.class, () -> processor.execute(session, sql)).getCode());
    }
    assertEquals(calls, ports.readCalls);
    ports.abortFailure = false;
    processor.execute(session, "ABORT");
    assertFalse(sessions.get(session).rollbackRequired());
    assertEquals(1, processor.execute(session, "SELECT").rows().size());
  }

  @Test
  void failedCloseRetainsSessionForCleanupRetry() {
    SessionId session = processor.openSession("client");
    processor.execute(session, "BEGIN");
    ports.abortFailure = true;
    assertThrows(DbmsException.class, () -> processor.closeSession(session));
    assertTrue(sessions.get(session).rollbackRequired());
    assertTrue(sessions.get(session).activeTx().isPresent());
    ports.abortFailure = false;
    processor.closeSession(session);
    processor.closeSession(session);
    assertTrue(sessions.find(session).isEmpty());
    assertThrows(DbmsException.class, () -> processor.execute(session, "SELECT"));
  }

  private QueryProcessorImpl processor() {
    var coordinator = new TransactionCoordinator(ports, ports, ports);
    var evaluator = new ExpressionEvaluator();
    var translator = new PlanTranslator(evaluator, ports, ports, coordinator);
    var writes = new WriteCoordinator(ports, ports);
    var delete = new DeleteExecutor(ports, writes, translator, evaluator, coordinator);
    var writer =
        new WriteExecutor(ports, writes, translator, evaluator, coordinator, ports, delete, ports);
    var engine =
        new ExecutionEngine(
            translator, new ReadExecutor(), writer, new TransactionCommandExecutor(coordinator));
    QueryOptimizer optimizer =
        new QueryOptimizer() {
          @Override
          public PreparedQuery prepare(String sql) {
            return switch (sql) {
              case "SELECT" -> new PreparedQuery(new TableScanPlan("t", "t"), SCHEMA);
              case "INDEX" ->
                  new PreparedQuery(
                      new CreateIndexPlan("idx", "t", "id", IndexKind.HASH), new Schema(List.of()));
              case "BEGIN", "COMMIT", "ABORT" ->
                  new PreparedQuery(
                      new TransactionCommandPlan(TransactionCommand.valueOf(sql)),
                      new Schema(List.of()));
              default -> throw new DbmsException(ErrorCode.SQL_SYNTAX, "bad query");
            };
          }

          @Override
          public double estimateCost(dynamitedb.contracts.query.PlanNode plan) {
            return 0;
          }

          @Override
          public PlanExplanation explain(String sql) {
            return new PlanExplanation("", "", 0);
          }
        };
    return new QueryProcessorImpl(optimizer, sessions, engine, coordinator);
  }

  private static final class Ports extends FakeComponents implements TupleReader {
    private boolean readFailure;
    private boolean abortFailure;
    private int readCalls;

    @Override
    public Schema getSchema(String table) {
      return SCHEMA;
    }

    @Override
    public void abort(TransactionId tx) {
      if (abortFailure) throw new DbmsException(ErrorCode.WAL_FAILURE, "rollback failed");
      super.abort(tx);
    }

    @Override
    public RowCursor openTableScan(String table, TransactionId tx) {
      readCalls++;
      if (readFailure) throw new DbmsException(ErrorCode.STORAGE_FAILURE, "read failed");
      var rows =
          List.of(new StoredRow(new RecordId(7, 0, 0, 0), new Row(List.of(new IntValue(7)))))
              .iterator();
      return new RowCursor() {
        @Override
        public Optional<StoredRow> next() {
          return rows.hasNext() ? Optional.of(rows.next()) : Optional.empty();
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
      return Optional.empty();
    }
  }
}
