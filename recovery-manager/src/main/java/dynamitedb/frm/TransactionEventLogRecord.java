package dynamitedb.frm;

import dynamitedb.contracts.LogSequenceNumber;
import dynamitedb.contracts.TransactionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TransactionEventLogRecord extends LogRecord {
  public final TxEvent event;
  public final Optional<Long> beginTimestamp;
  public final Optional<Instant> commitTime;

  public TransactionEventLogRecord(
      LogSequenceNumber lsn,
      TransactionId tx,
      Optional<LogSequenceNumber> prevLsn,
      UUID walLineage,
      TxEvent event,
      Optional<Long> beginTimestamp,
      Optional<Instant> commitTime) {
    super(lsn, Optional.of(tx), prevLsn, walLineage);
    this.event = Objects.requireNonNull(event, "event");
    this.beginTimestamp = Objects.requireNonNull(beginTimestamp, "beginTimestamp");
    this.commitTime = Objects.requireNonNull(commitTime, "commitTime");
    if ((event == TxEvent.BEGIN) != beginTimestamp.isPresent()
        || (event == TxEvent.COMMIT) != commitTime.isPresent()) {
      throw new IllegalArgumentException(
          "Timestamp belongs exactly to BEGIN; commit time exactly to COMMIT");
    }
  }
}
