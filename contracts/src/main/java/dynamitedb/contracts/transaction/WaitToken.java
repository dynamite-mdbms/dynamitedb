package dynamitedb.contracts.transaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies a pending request for a transaction; the provider validates ticket ownership and
 * liveness.
 */
public record WaitToken(TransactionId tx, UUID requestId) {
  public WaitToken {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(requestId, "requestId");
  }
}
