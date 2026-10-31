package dynamitedb.qp.session;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.SessionId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe session registry; transaction authority remains with CCM. */
public final class SessionManager {
  private final ConcurrentMap<SessionId, SessionContext> sessions = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();

  public SessionId create(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Client identity must not be blank");
    }
    long next = sequence.incrementAndGet();
    if (next <= 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Session identifiers exhausted");
    }
    SessionId id = new SessionId(next);
    sessions.put(id, new SessionContext(id, clientId));
    return id;
  }

  public SessionContext get(SessionId id) {
    return find(id)
        .orElseThrow(() -> new DbmsException(ErrorCode.INVALID_STATE, "Unknown session"));
  }

  public Optional<SessionContext> find(SessionId id) {
    return Optional.ofNullable(sessions.get(Objects.requireNonNull(id, "sessionId")));
  }

  /** Called after the facade has finished aborting any active transaction. */
  public void close(SessionId id) {
    find(id)
        .ifPresent(
            session -> {
              synchronized (session) {
                if (session.activeTx().isPresent()) {
                  throw new DbmsException(
                      ErrorCode.INVALID_STATE, "Abort the transaction before closing its session");
                }
                session.markClosed();
                sessions.remove(id, session);
              }
            });
  }
}
