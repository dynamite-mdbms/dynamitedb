package dynamitedb.contracts;

import java.util.Objects;
import java.util.UUID;

public record RecoveryStage(UUID id) {
  public RecoveryStage {
    Objects.requireNonNull(id, "id");
  }
}
