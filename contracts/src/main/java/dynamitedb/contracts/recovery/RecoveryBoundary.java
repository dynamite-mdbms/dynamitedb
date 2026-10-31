package dynamitedb.contracts.recovery;

import java.time.Instant;
import java.util.Optional;

/** Batas replay recovery historis (inklusif). */
public record RecoveryBoundary(LogSequenceNumber inclusiveLsn, Optional<Instant> commitTime) {}
