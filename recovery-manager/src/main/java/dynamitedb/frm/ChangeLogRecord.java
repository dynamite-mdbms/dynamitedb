package dynamitedb.frm;

import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ChangeLogRecord extends LogRecord {
  public final ChangeRecord change;

  public ChangeLogRecord(
      LogSequenceNumber lsn,
      Optional<LogSequenceNumber> prevLsn,
      UUID walLineage,
      ChangeRecord change) {
    super(lsn, Optional.of(Objects.requireNonNull(change, "change").tx()), prevLsn, walLineage);
    this.change = change;
  }
}
