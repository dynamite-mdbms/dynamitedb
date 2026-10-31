package dynamitedb.contracts;

import java.util.Objects;
import java.util.UUID;

public record SealedGeneration(UUID id, RecoveryBoundary boundary) {
  public SealedGeneration {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(boundary, "boundary");
  }
}
