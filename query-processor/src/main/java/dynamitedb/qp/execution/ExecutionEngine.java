package dynamitedb.qp.execution;

import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;

/** Arahin query: baca ke ReadExecutor, DML/DDL ke WriteExecutor, BEGIN/COMMIT/ABORT ke QP5. */
public class ExecutionEngine {

  // di CD final engine juga pegang ReadExecutor (QP3), WriteExecutor sama
  // TransactionCommandExecutor (QP5), ditambahin kalau kelasnya udah ada
  private final PlanTranslator translator;

  public ExecutionEngine(PlanTranslator translator) {
    this.translator = translator;
  }

  // CD final: execute(plan, session: SessionContext), sementara pake TransactionId
  // soalnya SessionContext (QP1) belum ada
  public QueryResult execute(PreparedQuery plan, TransactionId tx) {
    return new QueryResult(plan.outputSchema(), List.of(), 0, "");
  }
}
