package dynamitedb.contracts.recovery;

import dynamitedb.contracts.transaction.TransactionId;
import java.util.Objects;
import java.util.Optional;

public record LogFilter(Optional<TransactionId> tx, Optional<LogSequenceNumber> fromLsn) {
  public LogFilter {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(fromLsn, "fromLsn");
  }
}
