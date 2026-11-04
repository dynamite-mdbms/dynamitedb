package dynamitedb.ccm.protocol;

import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.Objects;

public class Strict2PLProtocol implements ConcurrencyProtocol {
  private final LockManager locks;

  public Strict2PLProtocol(LockManager locks) {
    this.locks = Objects.requireNonNull(locks, "locks");
  }

  @Override
  public AccessDecision request(AccessRequest request) {
    return locks.acquire(request);
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    return locks.awaitGrant(ticket);
  }

  /**
   * S and X locks remain held until onFinish; call it only after durable commit or completed
   * rollback.
   */
  @Override
  public void onFinish(TransactionId tx) {
    locks.releaseAll(tx);
  }
}
