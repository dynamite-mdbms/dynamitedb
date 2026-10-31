package dynamitedb.contracts.recovery;

import java.util.UUID;

/** Pilihan generasi storage dan WAL yang sedang aktif. */
public record GenerationManifest(
    UUID activeId, UUID walLineage, LogSequenceNumber boundaryLsn, RecoveryHighWater highWater) {}
