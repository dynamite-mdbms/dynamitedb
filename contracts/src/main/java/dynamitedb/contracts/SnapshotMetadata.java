package dynamitedb.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SnapshotMetadata(
    UUID id, UUID walLineage, LogSequenceNumber baseLsn, Instant createdAt) {
  public SnapshotMetadata {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(walLineage, "walLineage");
    Objects.requireNonNull(baseLsn, "baseLsn");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
