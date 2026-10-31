package dynamitedb.contracts.recovery;

import java.util.Objects;

public record CheckpointResult(LogSequenceNumber checkpointLsn) {
  public CheckpointResult {
    Objects.requireNonNull(checkpointLsn, "checkpointLsn");
  }
}
