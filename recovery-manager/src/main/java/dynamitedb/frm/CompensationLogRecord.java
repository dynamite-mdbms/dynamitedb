package dynamitedb.frm;

import dynamitedb.contracts.LogSequenceNumber;
import dynamitedb.contracts.RecoveryMutation;
import dynamitedb.contracts.TransactionId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CompensationLogRecord extends LogRecord {
  public final LogSequenceNumber compensates;
  public final Optional<LogSequenceNumber> undoNext;
  public final RecoveryMutation mutation;

  public CompensationLogRecord(
      LogSequenceNumber lsn,
      TransactionId tx,
      Optional<LogSequenceNumber> prevLsn,
      UUID walLineage,
      LogSequenceNumber compensates,
      Optional<LogSequenceNumber> undoNext,
      RecoveryMutation mutation) {
    super(lsn, Optional.of(tx), prevLsn, walLineage);
    this.compensates = Objects.requireNonNull(compensates, "compensates");
    this.undoNext = Objects.requireNonNull(undoNext, "undoNext");
    this.mutation = Objects.requireNonNull(mutation, "mutation");
  }
}
