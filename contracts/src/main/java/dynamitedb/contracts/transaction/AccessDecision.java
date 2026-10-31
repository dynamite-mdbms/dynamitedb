package dynamitedb.contracts.transaction;

import java.util.Optional;

// draf dari QP biar bisa compile, nanti ganti punya CCM
// ticket hanya ada kalau state WAITING
public record AccessDecision(AccessState state, Optional<WaitToken> ticket, String reason) {}
