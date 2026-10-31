package dynamitedb.sm.draft;

import java.time.Instant;
import java.util.Optional;

/** Temporary contract: Retained-history identifier and clock high-water state. */
public record RecoveryHighWater(
    LogSequenceNumber lsn,
    TransactionId maxTx,
    long maxTimestamp,
    Optional<Instant> maxCommitTime) {}
