package dynamitedb.qp.execution;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.session.SessionContext;
import java.util.List;

/** Arahin query: baca ke ReadExecutor, DML/DDL ke WriteExecutor, BEGIN/COMMIT/ABORT ke QP5. */
public class ExecutionEngine {

  private static final Schema NO_COLUMNS = new Schema(List.of());

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
    PlanNode root = plan.root();
    if (root instanceof TransactionCommandPlan command) {
      return transactionExecutor.execute(command.command(), session);
    }
    TransactionId tx = activeTx(session);
    return switch (root) {
      case InsertPlan insert -> affected(writer.insert(insert, tx));
      case UpdatePlan update -> affected(writer.update(update, tx));
      case DeletePlan delete -> affected(writer.delete(delete, tx));
      case CreateIndexPlan index -> {
        writer.createIndex(index, tx);
        yield new QueryResult(NO_COLUMNS, List.of(), 0, "Index " + index.index() + " created");
      }
      // sisanya plan relasional (SELECT)
      default -> reader.execute(translator.translate(root, tx), plan.outputSchema());
    };
  }

  // transaksi implisitnya udah dibuka QueryProcessorImpl sebelum masuk sini
  private static TransactionId activeTx(SessionContext session) {
    return session
        .activeTx()
        .orElseThrow(
            () -> new DbmsException(ErrorCode.INVALID_STATE, "No active transaction for query"));
  }

  private static QueryResult affected(long rows) {
    return new QueryResult(NO_COLUMNS, List.of(), rows, rows + " row(s) affected");
  }
}
