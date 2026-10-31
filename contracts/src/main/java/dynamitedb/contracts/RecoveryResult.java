package dynamitedb.contracts;

import java.util.Objects;

public record RecoveryResult(RecoveryStatus status, RecoveryHighWater highWater, String message) {
  public RecoveryResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(highWater, "highWater");
    Objects.requireNonNull(message, "message");
  }
}
