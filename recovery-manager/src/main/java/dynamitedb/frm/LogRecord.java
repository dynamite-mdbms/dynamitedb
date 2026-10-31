package dynamitedb.frm;

import dynamitedb.contracts.LogSequenceNumber;
import dynamitedb.contracts.TransactionId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable internal WAL envelope; no persistence is provided by Milestone 1 mocks. */
public abstract class LogRecord {
  public final LogSequenceNumber lsn;
  public final Optional<TransactionId> tx;
  public final Optional<LogSequenceNumber> prevLsn;
  public final UUID walLineage;

  protected LogRecord(
      LogSequenceNumber lsn,
      Optional<TransactionId> tx,
      Optional<LogSequenceNumber> prevLsn,
      UUID walLineage) {
    this.lsn = Objects.requireNonNull(lsn, "lsn");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.prevLsn = Objects.requireNonNull(prevLsn, "prevLsn");
    this.walLineage = Objects.requireNonNull(walLineage, "walLineage");
  }
}
