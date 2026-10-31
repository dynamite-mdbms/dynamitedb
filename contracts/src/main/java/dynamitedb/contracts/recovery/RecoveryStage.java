package dynamitedb.contracts.recovery;

import java.util.Objects;
import java.util.UUID;

/** Handle ruang kerja recovery historis yang terisolasi. */
public record RecoveryStage(UUID id) {
  public RecoveryStage {
    Objects.requireNonNull(id, "id");
  }
}
