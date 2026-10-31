package dynamitedb.sm.draft;

import java.time.Instant;
import java.util.UUID;

/** Temporary contract: Consistent snapshot identity and source lineage. */
public record SnapshotMetadata(
    UUID id, UUID walLineage, LogSequenceNumber baseLsn, Instant createdAt) {}
