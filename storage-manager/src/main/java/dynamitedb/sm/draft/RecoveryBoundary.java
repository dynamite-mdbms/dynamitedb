package dynamitedb.sm.draft;

import java.time.Instant;
import java.util.Optional;

/** Temporary contract: Inclusive historical replay boundary. */
public record RecoveryBoundary(LogSequenceNumber inclusiveLsn, Optional<Instant> commitTime) {}
