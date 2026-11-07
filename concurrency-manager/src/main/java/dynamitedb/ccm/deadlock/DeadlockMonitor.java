package dynamitedb.ccm.deadlock;

import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class DeadlockMonitor {
  private final LockManager locks;
  private final TransactionManager victims;

  public DeadlockMonitor(LockManager locks, TransactionManager victims) {
    this.locks = Objects.requireNonNull(locks, "locks");
    this.victims = Objects.requireNonNull(victims, "victims");
  }

  public Optional<TransactionCycle> findCycle() {
    Map<TransactionId, TransactionCycle> edges = locks.waitForGraph().edges();
    Set<TransactionId> active = new HashSet<>();
    Set<TransactionId> complete = new HashSet<>();
    List<TransactionId> path = new ArrayList<>();
    List<TransactionId> discoveredNodes = new ArrayList<>(edges.keySet());
    for (TransactionCycle edge : edges.values()) {
      discoveredNodes.addAll(edge.txIds());
    }
    List<TransactionId> nodes = discoveredNodes.stream().distinct().sorted().toList();
    for (TransactionId node : nodes) {
      if (!complete.contains(node)) {
        Optional<TransactionCycle> cycle = visit(node, edges, active, complete, path);
        if (cycle.isPresent()) {
          return cycle;
        }
      }
    }
    return Optional.empty();
  }

  public TransactionId selectVictim(List<TransactionId> cycle) {
    Objects.requireNonNull(cycle, "cycle");
    return cycle.stream()
        .max(Comparator.naturalOrder())
        .orElseThrow(() -> new IllegalArgumentException("Deadlock cycle must not be empty"));
  }

  public synchronized void resolveDeadlock() {
    Optional<TransactionCycle> cycle = findCycle();
    cycle.ifPresent(value -> victims.abort(selectVictim(value.txIds())));
  }

  private static Optional<TransactionCycle> visit(
      TransactionId current,
      Map<TransactionId, TransactionCycle> edges,
      Set<TransactionId> active,
      Set<TransactionId> complete,
      List<TransactionId> path) {
    active.add(current);
    path.add(current);
    TransactionCycle outgoing = edges.get(current);
    if (outgoing != null) {
      for (TransactionId target : outgoing.txIds().stream().sorted().toList()) {
        if (active.contains(target)) {
          int cycleStart = path.indexOf(target);
          return Optional.of(new TransactionCycle(path.subList(cycleStart, path.size())));
        }
        if (!complete.contains(target)) {
          Optional<TransactionCycle> cycle = visit(target, edges, active, complete, path);
          if (cycle.isPresent()) {
            return cycle;
          }
        }
      }
    }
    path.remove(path.size() - 1);
    active.remove(current);
    complete.add(current);
    return Optional.empty();
  }
}
