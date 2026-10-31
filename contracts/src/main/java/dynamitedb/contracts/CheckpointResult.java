package dynamitedb.contracts;

import java.util.Objects;

public record CheckpointResult(LogSequenceNumber checkpointLsn) {
  public CheckpointResult {
    Objects.requireNonNull(checkpointLsn, "checkpointLsn");
  }
}
