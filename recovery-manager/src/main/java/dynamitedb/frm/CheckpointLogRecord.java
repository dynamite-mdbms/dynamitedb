package dynamitedb.frm;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CheckpointLogRecord extends LogRecord {
  public final UUID checkpointId;
  public final CheckpointEvent event;
  public final LogSequenceNumber throughLsn;

  public CheckpointLogRecord(
      LogSequenceNumber lsn,
      UUID walLineage,
      UUID checkpointId,
      CheckpointEvent event,
      LogSequenceNumber throughLsn) {
    super(lsn, Optional.empty(), Optional.empty(), walLineage);
    this.checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
    this.event = Objects.requireNonNull(event, "event");
    this.throughLsn = Objects.requireNonNull(throughLsn, "throughLsn");
  }
}
