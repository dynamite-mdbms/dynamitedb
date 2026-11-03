package dynamitedb.frm;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Objects;
import java.util.Set;

public record RecoveryState(
    Set<TransactionId> winners,
    Set<TransactionId> losers,
    LogSequenceNumber lastLsn,
    RecoveryHighWater highWater) {
  public RecoveryState {
    winners = Set.copyOf(winners);
    losers = Set.copyOf(losers);
    Objects.requireNonNull(lastLsn, "lastLsn");
    Objects.requireNonNull(highWater, "highWater");
    if (winners.stream().anyMatch(losers::contains)) {
      throw new IllegalArgumentException("Winners and losers must be disjoint");
    }
  }
}
