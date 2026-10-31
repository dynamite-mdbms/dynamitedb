package dynamitedb.qp.execution;

import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.qp.session.SessionContext;
import java.util.List;

/** Arahin query: baca ke ReadExecutor, DML/DDL ke WriteExecutor, BEGIN/COMMIT/ABORT ke QP5. */
// udah sesuai CD final
public class ExecutionEngine {

  private final PlanTranslator translator;
  private final ReadExecutor reader;
  private final WriteExecutor writer;
  private final TransactionCommandExecutor transactionExecutor;

  public ExecutionEngine(
      PlanTranslator translator,
      ReadExecutor reader,
      WriteExecutor writer,
      TransactionCommandExecutor transactionExecutor) {
    this.translator = translator;
    this.reader = reader;
    this.writer = writer;
    this.transactionExecutor = transactionExecutor;
  }

  public QueryResult execute(PreparedQuery plan, SessionContext session) {
    return new QueryResult(plan.outputSchema(), List.of(), 0, "");
  }
}
