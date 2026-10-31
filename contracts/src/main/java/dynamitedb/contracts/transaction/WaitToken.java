package dynamitedb.contracts.transaction;

import java.util.UUID;

// draf dari QP biar bisa compile, nanti ganti punya CCM
public record WaitToken(TransactionId tx, UUID requestId) {}
