package dynamitedb.ccm.deadlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.transaction.TransactionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WaitForGraphTest {
  @Test
  void snapshotsEdgesAndAdjacencyLists() {
    TransactionId first = new TransactionId(1);
    TransactionId second = new TransactionId(2);
    List<TransactionId> blockers = new ArrayList<>(List.of(second));
    Map<TransactionId, TransactionCycle> edges = new HashMap<>();
    edges.put(first, new TransactionCycle(blockers));

    WaitForGraph graph = new WaitForGraph(edges);
    blockers.add(new TransactionId(3));
    edges.clear();

    assertEquals(List.of(second), graph.edges().get(first).txIds());
    assertThrows(UnsupportedOperationException.class, () -> graph.edges().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> graph.edges().get(first).txIds().add(new TransactionId(4)));
  }
}
