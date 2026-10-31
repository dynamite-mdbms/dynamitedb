package dynamitedb.sm.draft;

import java.util.UUID;

/** Temporary contract: Sealed storage generation prepared for publication. */
public record SealedGeneration(UUID id, RecoveryBoundary boundary) {}
