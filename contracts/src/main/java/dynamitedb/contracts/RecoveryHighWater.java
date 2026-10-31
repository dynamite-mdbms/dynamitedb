package dynamitedb.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RecoveryHighWater(
    LogSequenceNumber lsn,
    TransactionId maxTx,
    long maxTimestamp,
    Optional<Instant> maxCommitTime) {
  public RecoveryHighWater {
    Objects.requireNonNull(lsn, "lsn");
    Objects.requireNonNull(maxTx, "maxTx");
    Objects.requireNonNull(maxCommitTime, "maxCommitTime");
  }
}
