package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

public class SerializabilityChecker {
  public VerificationResult verify(Schedule schedule) {
    Map<TransactionId, Set<TransactionId>> adjacency = new HashMap<>();
    Map<TransactionId, Set<TransactionId>> incoming = new HashMap<>();
    Set<TransactionId> all = new TreeSet<>();

    List<ScheduledOperation> operations = schedule.operations();
    for (int i = 0; i < operations.size(); i++) {
      ScheduledOperation earlier = operations.get(i);
      TransactionId from = earlier.tx();
      all.add(from);
      for (int j = i + 1; j < operations.size(); j++) {
        ScheduledOperation later = operations.get(j);
        TransactionId to = later.tx();
        all.add(to);
        if (from.equals(to)) {
          continue;
        }
        if (earlier.resource().equals(later.resource())
            && (earlier.mode() != AccessMode.READ || later.mode() != AccessMode.READ)) {
          if (adjacency.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to)) {
            incoming.computeIfAbsent(to, key -> new LinkedHashSet<>()).add(from);
          }
        }
      }
    }

    List<TransactionId> order = topologicalOrder(all, adjacency, incoming);
    if (order.size() != all.size()) {
      return new VerificationResult(false, List.of());
    }
    return new VerificationResult(true, List.copyOf(order));
  }

  private static List<TransactionId> topologicalOrder(
      Set<TransactionId> all,
      Map<TransactionId, Set<TransactionId>> adjacency,
      Map<TransactionId, Set<TransactionId>> incoming) {
    Map<TransactionId, Integer> inDegree = new HashMap<>();
    for (TransactionId tx : all) {
      inDegree.put(tx, incoming.getOrDefault(tx, Set.of()).size());
    }
    PriorityQueue<TransactionId> ready = new PriorityQueue<>();
    for (TransactionId tx : all) {
      if (inDegree.get(tx) == 0) {
        ready.add(tx);
      }
    }
    List<TransactionId> order = new ArrayList<>();
    while (!ready.isEmpty()) {
      TransactionId tx = ready.poll();
      order.add(tx);
      for (TransactionId successor : adjacency.getOrDefault(tx, Set.of())) {
        int remaining = inDegree.get(successor) - 1;
        inDegree.put(successor, remaining);
        if (remaining == 0) {
          ready.add(successor);
        }
      }
    }
    return order;
  }
}
