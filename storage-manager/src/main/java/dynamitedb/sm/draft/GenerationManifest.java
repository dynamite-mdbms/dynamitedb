package dynamitedb.sm.draft;

import java.util.UUID;

/** Temporary contract: Single authoritative storage/WAL generation selection. */
public record GenerationManifest(
    UUID activeId, UUID walLineage, LogSequenceNumber boundaryLsn, RecoveryHighWater highWater) {}
