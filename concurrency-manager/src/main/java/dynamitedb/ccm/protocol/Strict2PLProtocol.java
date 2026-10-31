package dynamitedb.ccm.protocol;

import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;

public class Strict2PLProtocol implements ConcurrencyProtocol {
  private final LockManager locks = new LockManager();

  @Override
  public AccessDecision request(AccessRequest request) {
    return AccessDecision.granted();
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    return AccessDecision.granted();
  }

  @Override
  public void onFinish(TransactionId tx) {}
}
