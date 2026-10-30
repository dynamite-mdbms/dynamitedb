package dynamitedb.qp.execution;

import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.QueryResult;
import java.util.List;

/** Jalanin query: SELECT lewat PlanTranslator, write sama transaksi lewat QP5. */
public class ExecutionEngine {

  // di diagram engine juga pegang WriteExecutor sama TransactionCoordinator,
  // belum ditambahin soalnya kelas QP5 belum ada
  private final PlanTranslator translator;

  public ExecutionEngine(PlanTranslator translator) {
    this.translator = translator;
  }

  // diagram: execute(prepared, session), sementara pake ExecutionContext dulu
  // soalnya Session (QP1) belum ada
  public QueryResult execute(PreparedQuery prepared, ExecutionContext context) {
    return new QueryResult(List.of(), List.of(), 0, "");
  }
}
