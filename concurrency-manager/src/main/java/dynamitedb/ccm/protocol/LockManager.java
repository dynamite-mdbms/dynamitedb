package dynamitedb.ccm.protocol;

import dynamitedb.ccm.deadlock.TransactionCycle;
import dynamitedb.ccm.deadlock.WaitForGraph;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.ResourceKind;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Manages TABLE locks. A live ticket remains outstanding until consumed or cancelled, including
 * after grant; a different permission request for that transaction/resource is invalid meanwhile.
 * An already-held permission that satisfies the request remains immediately available.
 */
public class LockManager {
  private final Map<ResourceId, LockState> lockTable = new HashMap<>();
  private final Map<WaitToken, TicketEntry> tickets = new HashMap<>();

  public synchronized AccessDecision acquire(AccessRequest request) {
    if (request.resource().kind() != ResourceKind.TABLE) {
      throw new DbmsException(ErrorCode.UNSUPPORTED_OPERATION, "Only TABLE locks are supported");
    }
    LockState state = lockTable.get(request.resource());
    if (satisfies(state, request.tx(), request.mode())) {
      return AccessDecision.granted();
    }
    TicketEntry pending = pending(request.tx(), request.resource());
    if (pending != null) {
      if (pending.request.mode() == request.mode()) {
        return AccessDecision.waiting(pending.token, "waiting for lock");
      }
      throw invalidState("Transaction already has a different pending request");
    }
    if (state == null) {
      state = new LockState();
      lockTable.put(request.resource(), state);
    }
    if (state.waitQueue.isEmpty() && compatible(state, request.tx(), request.mode())) {
      grant(state, request.tx(), request.mode());
      return AccessDecision.granted();
    }
    WaitToken token = new WaitToken(request.tx(), UUID.randomUUID());
    TicketEntry entry = new TicketEntry(token, request);
    tickets.put(token, entry);
    state.waitQueue.add(token);
    return AccessDecision.waiting(token, "waiting for lock");
  }

  /**
   * Claims a live ticket once and waits for delivery. Unknown, stale, or already-claimed tickets
   * throw {@code INVALID_STATE}; cancellation after claim or interruption returns {@code ABORTED}.
   * A pre-existing or intervening interrupt cancels only this request, retains held locks, and
   * restores the interrupt flag.
   */
  public synchronized AccessDecision awaitGrant(WaitToken ticket) {
    TicketEntry entry = tickets.get(ticket);
    if (entry == null || entry.claimed || !entry.token.tx().equals(ticket.tx())) {
      throw invalidState("Unknown, stale, or already claimed ticket");
    }
    entry.claimed = true;
    try {
      if (Thread.currentThread().isInterrupted()) {
        return cancel(entry);
      }
      while (!entry.granted && !entry.cancelled) {
        try {
          wait();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return cancel(entry);
        }
      }
      if (Thread.currentThread().isInterrupted()) {
        return cancel(entry);
      }
      if (entry.cancelled) {
        return AccessDecision.aborted("request cancelled");
      }
      tickets.remove(entry.token);
      return AccessDecision.granted();
    } finally {
      notifyAll();
    }
  }

  /**
   * Cancels outstanding tickets and releases all locks for {@code tx}. Call only after durable
   * commit or completed rollback; registered awaiters return {@code ABORTED}. Interrupt
   * cancellation alone never releases held locks.
   */
  public synchronized void releaseAll(TransactionId tx) {
    Set<ResourceId> affected = new HashSet<>();
    for (TicketEntry entry : new ArrayList<>(tickets.values())) {
      if (entry.request.tx().equals(tx)) {
        tickets.remove(entry.token);
        LockState state = lockTable.get(entry.request.resource());
        if (state != null) {
          state.waitQueue.remove(entry.token);
          affected.add(entry.request.resource());
        }
        if (entry.claimed) {
          entry.cancelled = true;
        }
      }
    }
    for (var tableEntry : new ArrayList<>(lockTable.entrySet())) {
      ResourceId resource = tableEntry.getKey();
      LockState state = tableEntry.getValue();
      boolean releasedOwnership = state.sharedOwners.remove(tx);
      if (state.exclusiveOwner.filter(tx::equals).isPresent()) {
        state.exclusiveOwner = Optional.empty();
        releasedOwnership = true;
      }
      if (releasedOwnership || !state.waitQueue.isEmpty()) {
        affected.add(resource);
      }
    }
    for (ResourceId resource : affected) {
      LockState state = lockTable.get(resource);
      if (state != null) {
        drain(resource, state);
      }
    }
    lockTable
        .entrySet()
        .removeIf(
            entry ->
                entry.getValue().sharedOwners.isEmpty()
                    && entry.getValue().exclusiveOwner.isEmpty()
                    && entry.getValue().waitQueue.isEmpty());
    notifyAll();
  }

  public synchronized WaitForGraph waitForGraph() {
    Map<TransactionId, Set<TransactionId>> adjacency = new TreeMap<>();
    for (LockState state : lockTable.values()) {
      List<TicketEntry> earlier = new ArrayList<>();
      for (WaitToken token : state.waitQueue) {
        TicketEntry current = tickets.get(token);
        if (current == null) {
          continue;
        }
        TransactionId source = current.request.tx();
        Set<TransactionId> targets = adjacency.computeIfAbsent(source, ignored -> new TreeSet<>());
        state.exclusiveOwner.ifPresent(owner -> addBlocker(targets, source, owner));
        if (current.request.mode() == AccessMode.WRITE) {
          state.sharedOwners.forEach(owner -> addBlocker(targets, source, owner));
        }
        for (TicketEntry previous : earlier) {
          if (queueIncompatible(previous.request.mode(), current.request.mode())) {
            addBlocker(targets, source, previous.request.tx());
          }
        }
        earlier.add(current);
      }
    }
    Map<TransactionId, TransactionCycle> edges = new TreeMap<>();
    adjacency.forEach(
        (source, targets) -> {
          if (!targets.isEmpty()) {
            edges.put(source, new TransactionCycle(List.copyOf(targets)));
          }
        });
    return new WaitForGraph(Map.copyOf(edges));
  }

  private TicketEntry pending(TransactionId tx, ResourceId resource) {
    for (TicketEntry entry : tickets.values()) {
      if (entry.request.tx().equals(tx) && entry.request.resource().equals(resource)) {
        return entry;
      }
    }
    return null;
  }

  private AccessDecision cancel(TicketEntry entry) {
    entry.cancelled = true;
    tickets.remove(entry.token);
    LockState state = lockTable.get(entry.request.resource());
    if (state != null) {
      state.waitQueue.remove(entry.token);
      drain(entry.request.resource(), state);
      removeIfUnused(entry.request.resource(), state);
    }
    notifyAll();
    return AccessDecision.aborted("request cancelled");
  }

  private void drain(ResourceId resource, LockState state) {
    while (!state.waitQueue.isEmpty()) {
      WaitToken token = state.waitQueue.get(0);
      TicketEntry entry = tickets.get(token);
      if (entry == null) {
        state.waitQueue.remove(0);
        continue;
      }
      if (!compatible(state, entry.request.tx(), entry.request.mode())) {
        break;
      }
      state.waitQueue.remove(0);
      grant(state, entry.request.tx(), entry.request.mode());
      entry.granted = true;
      if (entry.request.mode() == AccessMode.WRITE) {
        break;
      }
    }
    removeIfUnused(resource, state);
    notifyAll();
  }

  private void removeIfUnused(ResourceId resource, LockState state) {
    if (state.sharedOwners.isEmpty()
        && state.exclusiveOwner.isEmpty()
        && state.waitQueue.isEmpty()) {
      lockTable.remove(resource, state);
    }
  }

  private static void addBlocker(
      Set<TransactionId> targets, TransactionId source, TransactionId target) {
    if (!source.equals(target)) {
      targets.add(target);
    }
  }

  private static boolean queueIncompatible(AccessMode earlier, AccessMode requested) {
    return requested == AccessMode.WRITE || earlier == AccessMode.WRITE;
  }

  private static boolean satisfies(LockState state, TransactionId tx, AccessMode mode) {
    return state != null
        && (state.exclusiveOwner.filter(tx::equals).isPresent()
            || (mode == AccessMode.READ && state.sharedOwners.contains(tx)));
  }

  private static boolean compatible(LockState state, TransactionId tx, AccessMode mode) {
    if (state.exclusiveOwner.isPresent() && !state.exclusiveOwner.orElseThrow().equals(tx)) {
      return false;
    }
    return mode == AccessMode.READ
        ? state.exclusiveOwner.isEmpty() || state.exclusiveOwner.orElseThrow().equals(tx)
        : state.sharedOwners.stream().allMatch(tx::equals);
  }

  private static void grant(LockState state, TransactionId tx, AccessMode mode) {
    if (mode == AccessMode.READ) {
      if (state.exclusiveOwner.filter(tx::equals).isEmpty()) {
        state.sharedOwners.add(tx);
      }
    } else {
      state.sharedOwners.remove(tx);
      state.exclusiveOwner = Optional.of(tx);
    }
  }

  private static DbmsException invalidState(String message) {
    return new DbmsException(ErrorCode.INVALID_STATE, message);
  }

  private static final class TicketEntry {
    private final WaitToken token;
    private final AccessRequest request;
    private boolean claimed;
    private boolean granted;
    private boolean cancelled;

    private TicketEntry(WaitToken token, AccessRequest request) {
      this.token = token;
      this.request = request;
    }
  }
}
