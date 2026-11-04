package dynamitedb.contracts.recovery;

import java.util.Objects;
import java.util.UUID;

/** Pilihan generasi storage dan WAL yang sedang aktif. */
public record GenerationManifest(
    UUID activeId, UUID walLineage, LogSequenceNumber boundaryLsn, RecoveryHighWater highWater) {
  public GenerationManifest {
    Objects.requireNonNull(activeId, "activeId");
    Objects.requireNonNull(walLineage, "walLineage");
    Objects.requireNonNull(boundaryLsn, "boundaryLsn");
    Objects.requireNonNull(highWater, "highWater");
  }
}
