package dynamitedb.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RecoveryBoundary(LogSequenceNumber inclusiveLsn, Optional<Instant> commitTime) {
  public RecoveryBoundary {
    Objects.requireNonNull(inclusiveLsn, "inclusiveLsn");
    Objects.requireNonNull(commitTime, "commitTime");
  }
}
