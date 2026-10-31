package dynamitedb.qp.transaction;

import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.qp.session.SessionContext;
import java.util.List;

/** Menjalankan perintah BEGIN, COMMIT, dan ABORT dari klien (QP-12). */
public class TransactionCommandExecutor {

  private final TransactionCoordinator coordinator;

  public TransactionCommandExecutor(TransactionCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  public QueryResult execute(TransactionCommand cmd, SessionContext session) {
    switch (cmd) {
      case BEGIN -> coordinator.begin(session);
      case COMMIT -> coordinator.commit(session);
      case ABORT -> coordinator.abort(session);
    }
    return new QueryResult(List.of(), List.of(), 0, cmd.name());
  }
}
