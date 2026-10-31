package dynamitedb.ccm.protocol;

import dynamitedb.ccm.deadlock.WaitForGraph;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.Map;

public class LockManager {
  private final Map<ResourceId, LockState> lockTable = Map.of();

  public AccessDecision acquire(AccessRequest request) {
    return AccessDecision.granted();
  }

  public AccessDecision awaitGrant(WaitToken ticket) {
    return AccessDecision.granted();
  }

  public void releaseAll(TransactionId tx) {}

  public WaitForGraph waitForGraph() {
    return new WaitForGraph(Map.of());
  }
}
