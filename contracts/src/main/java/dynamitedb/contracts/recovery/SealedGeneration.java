package dynamitedb.contracts.recovery;

import java.util.UUID;

/** Generasi storage yang sudah disegel dan siap dipublikasikan. */
public record SealedGeneration(UUID id, RecoveryBoundary boundary) {}
