package dynamitedb.ccm.deadlock;

import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;
import java.util.Objects;

public record TransactionCycle(List<TransactionId> txIds) {
  public TransactionCycle {
    txIds = List.copyOf(Objects.requireNonNull(txIds, "txIds"));
  }
}
