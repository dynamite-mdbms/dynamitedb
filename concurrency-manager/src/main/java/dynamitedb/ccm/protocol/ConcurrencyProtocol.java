package dynamitedb.ccm.protocol;

import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;

public interface ConcurrencyProtocol {
  AccessDecision request(AccessRequest request);

  AccessDecision awaitAccess(WaitToken ticket);

  void onFinish(TransactionId tx);
}
