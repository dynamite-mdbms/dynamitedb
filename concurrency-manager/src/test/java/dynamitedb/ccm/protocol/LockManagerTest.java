package dynamitedb.ccm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.deadlock.WaitForGraph;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class LockManagerTest {
  private final LockManager manager = new LockManager();

  @Test
  void sharesReadsAndKeepsLocksUntilRelease() {
    var resource = ResourceId.table(1);
    var first = tx(1);
    var second = tx(2);
    assertGranted(manager.acquire(request(first, resource, AccessMode.READ)));
    assertGranted(manager.acquire(request(second, resource, AccessMode.READ)));
    assertGranted(manager.acquire(request(first, resource, AccessMode.READ)));
    assertWaiting(manager.acquire(request(tx(3), resource, AccessMode.WRITE)));
    manager.releaseAll(first);
    assertEquals(Set.of(tx(3)), manager.waitForGraph().edges().keySet());
    manager.releaseAll(second);
    assertTrue(manager.waitForGraph().edges().isEmpty());
  }

  @Test
  void supportsIndependentResourcesAndExclusiveReentrancy() {
    assertGranted(manager.acquire(request(tx(1), ResourceId.table(1), AccessMode.WRITE)));
    assertGranted(manager.acquire(request(tx(1), ResourceId.table(1), AccessMode.READ)));
    assertGranted(manager.acquire(request(tx(2), ResourceId.table(2), AccessMode.WRITE)));
    assertWaiting(manager.acquire(request(tx(2), ResourceId.table(1), AccessMode.READ)));
  }

  @Test
  void queuesFifoAndGrantsConsecutiveReadersAsBatch() throws Exception {
    var resource = ResourceId.table(1);
    var holder = tx(1);
    var writer = tx(2);
    var reader1 = tx(3);
    var reader2 = tx(4);
    assertGranted(manager.acquire(request(holder, resource, AccessMode.READ)));
    WaitToken writeTicket = ticket(manager.acquire(request(writer, resource, AccessMode.WRITE)));
    WaitToken read1Ticket = ticket(manager.acquire(request(reader1, resource, AccessMode.READ)));
    WaitToken read2Ticket = ticket(manager.acquire(request(reader2, resource, AccessMode.READ)));
    manager.releaseAll(holder);
    assertEquals(AccessState.GRANTED, manager.awaitGrant(writeTicket).state());
    assertEquals(
        AccessState.WAITING, manager.acquire(request(tx(5), resource, AccessMode.READ)).state());
    manager.releaseAll(writer);
    assertEquals(AccessState.GRANTED, manager.awaitGrant(read1Ticket).state());
    assertEquals(AccessState.GRANTED, manager.awaitGrant(read2Ticket).state());
  }

  @Test
  void identicalPendingRequestReusesTicketButDifferentModeIsInvalid() {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var write = manager.acquire(request(tx(2), resource, AccessMode.READ));
    assertEquals(
        write.ticket(), manager.acquire(request(tx(2), resource, AccessMode.READ)).ticket());
    assertInvalid(() -> manager.acquire(request(tx(2), resource, AccessMode.WRITE)));
  }

  @Test
  void upgradeRetainsSharedOwnershipAndUpgradeCycleIsVisible() {
    var resource = ResourceId.table(1);
    var first = tx(1);
    var second = tx(2);
    assertGranted(manager.acquire(request(first, resource, AccessMode.READ)));
    assertGranted(manager.acquire(request(second, resource, AccessMode.READ)));
    var firstUpgrade = ticket(manager.acquire(request(first, resource, AccessMode.WRITE)));
    var secondUpgrade = ticket(manager.acquire(request(second, resource, AccessMode.WRITE)));
    assertEquals(List.of(second), manager.waitForGraph().edges().get(first).txIds());
    assertEquals(List.of(first), manager.waitForGraph().edges().get(second).txIds());
    manager.releaseAll(first);
    assertEquals(AccessState.GRANTED, manager.awaitGrant(secondUpgrade).state());
    assertInvalid(() -> manager.awaitGrant(firstUpgrade));
  }

  @Test
  void rejectsRecordResourcesBeforeChangingState() {
    var request =
        new AccessRequest(
            tx(1),
            new ResourceId(
                dynamitedb.contracts.transaction.ResourceKind.RECORD,
                1,
                java.util.Optional.of(new dynamitedb.contracts.storage.RecordId(1, 1, 1, 1))),
            AccessMode.READ);
    var exception = assertThrows(DbmsException.class, () -> manager.acquire(request));
    assertEquals(ErrorCode.UNSUPPORTED_OPERATION, exception.getCode());
    assertTrue(manager.waitForGraph().edges().isEmpty());
  }

  @Test
  void grantsBeforeAwaitAndTicketsAreOneShotBearers() {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var token = ticket(manager.acquire(request(tx(2), resource, AccessMode.WRITE)));
    manager.releaseAll(tx(1));
    assertEquals(
        AccessState.GRANTED,
        manager.awaitGrant(new WaitToken(token.tx(), token.requestId())).state());
    assertInvalid(() -> manager.awaitGrant(token));
  }

  @Test
  void rejectsForgedForeignMismatchedAndStaleTickets() {
    var forged = new WaitToken(tx(1), UUID.randomUUID());
    assertInvalid(() -> manager.awaitGrant(forged));
    var otherManager = new LockManager();
    assertGranted(otherManager.acquire(request(tx(1), ResourceId.table(1), AccessMode.WRITE)));
    var liveElsewhere =
        ticket(otherManager.acquire(request(tx(2), ResourceId.table(1), AccessMode.READ)));
    assertInvalid(() -> manager.awaitGrant(liveElsewhere));
    assertGranted(manager.acquire(request(tx(1), ResourceId.table(1), AccessMode.WRITE)));
    var ticket = ticket(manager.acquire(request(tx(2), ResourceId.table(1), AccessMode.READ)));
    assertInvalid(() -> manager.awaitGrant(new WaitToken(tx(3), ticket.requestId())));
    manager.releaseAll(tx(2));
    assertInvalid(() -> manager.awaitGrant(ticket));
  }

  @Test
  void releaseCancelsWaiterAndRemovesAllState() throws Exception {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var token = ticket(manager.acquire(request(tx(2), resource, AccessMode.READ)));
    var executor = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch entered = new CountDownLatch(1);
      Future<AccessDecision> result =
          executor.submit(
              () -> {
                synchronized (manager) {
                  entered.countDown();
                  return manager.awaitGrant(token);
                }
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      synchronized (manager) {
        manager.releaseAll(tx(2));
      }
      assertEquals(AccessState.ABORTED, result.get(2, TimeUnit.SECONDS).state());
      manager.releaseAll(tx(1));
      assertTrue(manager.waitForGraph().edges().isEmpty());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void releaseCancelsQueuedUpgradeAndDrainsFollowingReaderWithoutReleasingOtherOwner() {
    var resource = ResourceId.table(1);
    var first = tx(1);
    var cancelled = tx(2);
    var successor = tx(3);
    assertGranted(manager.acquire(request(first, resource, AccessMode.READ)));
    assertGranted(manager.acquire(request(cancelled, resource, AccessMode.READ)));
    var cancelledTicket = ticket(manager.acquire(request(cancelled, resource, AccessMode.WRITE)));
    var successorTicket = ticket(manager.acquire(request(successor, resource, AccessMode.READ)));

    manager.releaseAll(cancelled);

    assertInvalid(() -> manager.awaitGrant(cancelledTicket));
    assertEquals(AccessState.GRANTED, manager.awaitGrant(successorTicket).state());
    assertWaiting(manager.acquire(request(tx(4), resource, AccessMode.WRITE)));
  }

  @Test
  void interruptCancelsQueuedUpgradeAndDrainsFollowingReaderButRetainsLocks() throws Exception {
    var resource = ResourceId.table(1);
    var first = tx(1);
    var cancelled = tx(2);
    var successor = tx(3);
    assertGranted(manager.acquire(request(first, resource, AccessMode.READ)));
    assertGranted(manager.acquire(request(cancelled, resource, AccessMode.READ)));
    var cancelledTicket = ticket(manager.acquire(request(cancelled, resource, AccessMode.WRITE)));
    var successorTicket = ticket(manager.acquire(request(successor, resource, AccessMode.READ)));
    var thread = new AtomicReference<Thread>();
    var executor = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch entered = new CountDownLatch(1);
      Future<Boolean> interrupted =
          executor.submit(
              () -> {
                synchronized (manager) {
                  thread.set(Thread.currentThread());
                  entered.countDown();
                  assertEquals(AccessState.ABORTED, manager.awaitGrant(cancelledTicket).state());
                  return Thread.currentThread().isInterrupted();
                }
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      synchronized (manager) {
        thread.get().interrupt();
      }
      assertTrue(interrupted.get(2, TimeUnit.SECONDS));
      assertEquals(AccessState.GRANTED, manager.awaitGrant(successorTicket).state());
      assertWaiting(manager.acquire(request(tx(4), resource, AccessMode.WRITE)));
      assertEquals(
          List.of(first, cancelled, successor), manager.waitForGraph().edges().get(tx(4)).txIds());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
      manager.releaseAll(first);
      manager.releaseAll(cancelled);
      manager.releaseAll(successor);
      manager.releaseAll(tx(4));
    }
  }

  @Test
  void unconsumedGrantBlocksDifferentModeButAllowsAlreadyHeldPermission() {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var token = ticket(manager.acquire(request(tx(2), resource, AccessMode.READ)));
    manager.releaseAll(tx(1));

    assertGranted(manager.acquire(request(tx(2), resource, AccessMode.READ)));
    assertInvalid(() -> manager.acquire(request(tx(2), resource, AccessMode.WRITE)));
    assertEquals(AccessState.GRANTED, manager.awaitGrant(token).state());
  }

  @Test
  void preInterruptedAwaitAbortsAndKeepsGrantedLock() {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var token = ticket(manager.acquire(request(tx(2), resource, AccessMode.WRITE)));
    manager.releaseAll(tx(1));
    Thread.currentThread().interrupt();
    try {
      assertEquals(AccessState.ABORTED, manager.awaitGrant(token).state());
      assertTrue(Thread.currentThread().isInterrupted());
      assertWaiting(manager.acquire(request(tx(3), resource, AccessMode.WRITE)));
    } finally {
      Thread.interrupted();
      manager.releaseAll(tx(2));
    }
  }

  @Test
  void interruptWhileWaitingCancelsOnlyTheUpgradeAndRestoresFlag() throws Exception {
    var resource = ResourceId.table(1);
    var first = tx(1);
    var second = tx(2);
    assertGranted(manager.acquire(request(first, resource, AccessMode.READ)));
    assertGranted(manager.acquire(request(second, resource, AccessMode.READ)));
    var token = ticket(manager.acquire(request(first, resource, AccessMode.WRITE)));
    var thread = new AtomicReference<Thread>();
    var executor = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch entered = new CountDownLatch(1);
      Future<Boolean> interrupted =
          executor.submit(
              () -> {
                synchronized (manager) {
                  thread.set(Thread.currentThread());
                  entered.countDown();
                  AccessDecision result = manager.awaitGrant(token);
                  assertEquals(AccessState.ABORTED, result.state());
                  return Thread.currentThread().isInterrupted();
                }
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      synchronized (manager) {
        thread.get().interrupt();
      }
      assertTrue(interrupted.get(2, TimeUnit.SECONDS));
      assertWaiting(manager.acquire(request(tx(3), resource, AccessMode.WRITE)));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
      manager.releaseAll(first);
      manager.releaseAll(second);
      manager.releaseAll(tx(3));
    }
  }

  @Test
  void releaseAfterGrantButBeforeDeliveryAbortsClaimedTicket() throws Exception {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var token = ticket(manager.acquire(request(tx(2), resource, AccessMode.WRITE)));
    var executor = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch entered = new CountDownLatch(1);
      Future<AccessDecision> result =
          executor.submit(
              () -> {
                synchronized (manager) {
                  entered.countDown();
                  return manager.awaitGrant(token);
                }
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      synchronized (manager) {
        manager.releaseAll(tx(1));
        manager.releaseAll(tx(2));
      }
      assertEquals(AccessState.ABORTED, result.get(2, TimeUnit.SECONDS).state());
      assertGranted(manager.acquire(request(tx(3), resource, AccessMode.WRITE)));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
      manager.releaseAll(tx(3));
    }
  }

  @Test
  void grantBeforeInterruptDeliveryAbortsButRetainsGrantedLock() throws Exception {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var token = ticket(manager.acquire(request(tx(2), resource, AccessMode.WRITE)));
    var thread = new AtomicReference<Thread>();
    var executor = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch entered = new CountDownLatch(1);
      Future<Boolean> result =
          executor.submit(
              () -> {
                synchronized (manager) {
                  thread.set(Thread.currentThread());
                  entered.countDown();
                  AccessDecision decision = manager.awaitGrant(token);
                  assertEquals(AccessState.ABORTED, decision.state());
                  return Thread.currentThread().isInterrupted();
                }
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      synchronized (manager) {
        manager.releaseAll(tx(1));
        thread.get().interrupt();
      }
      assertTrue(result.get(2, TimeUnit.SECONDS));
      assertWaiting(manager.acquire(request(tx(3), resource, AccessMode.WRITE)));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
      manager.releaseAll(tx(2));
      manager.releaseAll(tx(3));
    }
  }

  @Test
  void graphIncludesOwnersAndEarlierIncompatibleQueueRequestsAsImmutableSnapshot() {
    var resource = ResourceId.table(1);
    var owner = tx(1);
    var writer = tx(2);
    var reader = tx(3);
    assertGranted(manager.acquire(request(owner, resource, AccessMode.READ)));
    ticket(manager.acquire(request(writer, resource, AccessMode.WRITE)));
    ticket(manager.acquire(request(reader, resource, AccessMode.READ)));
    WaitForGraph snapshot = manager.waitForGraph();
    assertEquals(List.of(owner), snapshot.edges().get(writer).txIds());
    assertEquals(List.of(writer), snapshot.edges().get(reader).txIds());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.edges().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.edges().get(reader).txIds().clear());
    manager.releaseAll(owner);
    assertEquals(List.of(owner), snapshot.edges().get(writer).txIds());
  }

  @Test
  void graphDeduplicatesBlockersAcrossResourcesAndReleaseIsIdempotent() {
    var owner = tx(1);
    var waiter = tx(2);
    var tickets = new ArrayList<WaitToken>();
    for (long table = 1; table <= 2; table++) {
      assertGranted(manager.acquire(request(owner, ResourceId.table(table), AccessMode.WRITE)));
      tickets.add(
          ticket(manager.acquire(request(waiter, ResourceId.table(table), AccessMode.READ))));
    }
    assertEquals(List.of(owner), manager.waitForGraph().edges().get(waiter).txIds());
    manager.releaseAll(owner);
    manager.releaseAll(owner);
    for (WaitToken token : tickets) {
      assertEquals(AccessState.GRANTED, manager.awaitGrant(token).state());
    }
  }

  @Test
  void duplicateConcurrentAwaitCannotCancelClaimant() throws Exception {
    var resource = ResourceId.table(1);
    assertGranted(manager.acquire(request(tx(1), resource, AccessMode.WRITE)));
    var token = ticket(manager.acquire(request(tx(2), resource, AccessMode.READ)));
    var executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch started = new CountDownLatch(1);
      Future<AccessDecision> claimant =
          executor.submit(
              () -> {
                synchronized (manager) {
                  started.countDown();
                  return manager.awaitGrant(token);
                }
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      synchronized (manager) {
        assertInvalid(() -> manager.awaitGrant(token));
      }
      manager.releaseAll(tx(1));
      assertEquals(AccessState.GRANTED, claimant.get(2, TimeUnit.SECONDS).state());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }
  }

  private static TransactionId tx(long id) {
    return new TransactionId(id);
  }

  private static AccessRequest request(TransactionId tx, ResourceId resource, AccessMode mode) {
    return new AccessRequest(tx, resource, mode);
  }

  private static void assertGranted(AccessDecision decision) {
    assertEquals(AccessState.GRANTED, decision.state());
  }

  private static void assertWaiting(AccessDecision decision) {
    assertEquals(AccessState.WAITING, decision.state());
  }

  private static WaitToken ticket(AccessDecision decision) {
    assertWaiting(decision);
    return decision.ticket().orElseThrow();
  }

  private static void assertInvalid(Runnable operation) {
    var exception = assertThrows(DbmsException.class, operation::run);
    assertEquals(ErrorCode.INVALID_STATE, exception.getCode());
  }
}
