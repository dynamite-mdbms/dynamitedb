package dynamitedb.contracts.recovery;

import java.time.Instant;
import java.util.UUID;

/** Identitas snapshot beserta lineage WAL sumbernya. */
public record SnapshotMetadata(
    UUID id, UUID walLineage, LogSequenceNumber baseLsn, Instant createdAt) {}
