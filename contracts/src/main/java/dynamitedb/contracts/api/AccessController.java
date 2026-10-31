package dynamitedb.contracts.api;

import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.WaitToken;

// draf dari QP biar bisa compile, nanti ganti punya CC1
public interface AccessController {

  AccessDecision requestAccess(AccessRequest request);

  AccessDecision awaitAccess(WaitToken ticket);
}
