package dynamitedb.contracts.api;

import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.WaitToken;

public interface AccessController {
  AccessDecision requestAccess(AccessRequest request);

  AccessDecision awaitAccess(WaitToken ticket);
}
