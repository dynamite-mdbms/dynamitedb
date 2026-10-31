package dynamitedb.qp.session;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Objects;
import java.util.Optional;

/** Per-client state. QP serializes requests and teardown on this object's monitor. */
public class SessionContext {

  private final SessionId id;
  private final String clientId;
  private volatile Optional<TransactionId> activeTx = Optional.empty();
  private volatile boolean rollbackRequired;
  private volatile boolean closed;

  public SessionContext(SessionId id, String clientId) {
    this.id = Objects.requireNonNull(id, "id");
    this.clientId = Objects.requireNonNull(clientId, "clientId");
    if (id.value() <= 0 || clientId.isBlank()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Invalid session identity");
    }
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
    requireOpen();
    this.activeTx = Objects.requireNonNull(activeTx, "activeTx");
  }

  public boolean rollbackRequired() {
    return rollbackRequired;
  }

  public void markRollbackRequired() {
    requireOpen();
    rollbackRequired = true;
  }

  public void completeRollback() {
    requireOpen();
    if (activeTx.isPresent()) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Rollback did not clear the active transaction");
    }
    rollbackRequired = false;
  }

  public void requireOpen() {
    if (closed) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Session is closed");
    }
  }

  void markClosed() {
    closed = true;
  }
}
