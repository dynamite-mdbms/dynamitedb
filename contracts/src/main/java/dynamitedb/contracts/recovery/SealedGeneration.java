package dynamitedb.contracts.recovery;

import java.util.Objects;
import java.util.UUID;

/** Generasi storage yang sudah disegel dan siap dipublikasikan. */
public record SealedGeneration(UUID id, RecoveryBoundary boundary) {
  public SealedGeneration {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(boundary, "boundary");
  }
}
