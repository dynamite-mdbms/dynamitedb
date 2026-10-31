package dynamitedb.contracts.recovery;

import dynamitedb.contracts.transaction.TransactionId;
import java.time.Instant;
import java.util.Optional;

public record RecoveryHighWater(
    LogSequenceNumber lsn,
    TransactionId maxTx,
    long maxTimestamp,
    Optional<Instant> maxCommitTime) {}
