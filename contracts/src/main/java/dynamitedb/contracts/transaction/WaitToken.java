package dynamitedb.contracts.transaction;

import java.util.Objects;
import java.util.UUID;

public record WaitToken(TransactionId tx, UUID requestId) {
  public WaitToken {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(requestId, "requestId");
  }
}
