package dynamitedb.contracts.recovery;

import dynamitedb.contracts.transaction.TransactionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RecoveryTarget(Optional<Instant> commitTime, Optional<TransactionId> committedTx) {
  public RecoveryTarget {
    Objects.requireNonNull(commitTime, "commitTime");
    Objects.requireNonNull(committedTx, "committedTx");
    if (commitTime.isPresent() == committedTx.isPresent()) {
      throw new IllegalArgumentException("Exactly one recovery target selector must be present");
    }
  }
}
