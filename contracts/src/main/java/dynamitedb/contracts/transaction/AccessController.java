package dynamitedb.contracts.transaction;

// draf dari QP biar bisa compile, nanti ganti punya CC1
public interface AccessController {

  AccessDecision requestAccess(AccessRequest request);

  AccessDecision awaitAccess(WaitToken ticket);
}
