package dynamitedb.frm;

import dynamitedb.contracts.LogSequenceNumber;
import dynamitedb.contracts.RecoveryHighWater;
import dynamitedb.contracts.TransactionId;
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
