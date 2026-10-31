package dynamitedb.qp.session;

import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Optional;

// draf dari QP5 biar bisa compile, nanti ganti punya QP1
public class SessionContext {

  private final SessionId id;
  private final String clientId;
  private Optional<TransactionId> activeTx = Optional.empty();

  public SessionContext(SessionId id, String clientId) {
    this.id = id;
    this.clientId = clientId;
  }

  public SessionId id() {
    return id;
  }

  public String clientId() {
    return clientId;
  }

  public Optional<TransactionId> activeTx() {
    return activeTx;
  }

  public void setActiveTx(Optional<TransactionId> activeTx) {
    this.activeTx = activeTx;
  }
}
