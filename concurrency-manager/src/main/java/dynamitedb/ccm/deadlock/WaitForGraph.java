package dynamitedb.ccm.deadlock;

import dynamitedb.contracts.transaction.TransactionId;
import java.util.Map;
import java.util.Objects;

public record WaitForGraph(Map<TransactionId, TransactionCycle> edges) {
  public WaitForGraph {
    edges = Map.copyOf(Objects.requireNonNull(edges, "edges"));
  }
}
