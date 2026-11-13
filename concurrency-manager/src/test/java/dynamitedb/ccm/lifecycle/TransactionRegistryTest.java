package dynamitedb.ccm.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransactionRegistryTest {

  @Test
  void idsAndTimestampsStayUniqueAndIncreasingUnderConcurrentBegin() throws Exception {
    var registry = new TransactionRegistry();
    int threads = 8;
    int perThread = 250;
    var ids = ConcurrentHashMap.<Long>newKeySet();
    var timestamps = ConcurrentHashMap.<Long>newKeySet();
    var start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      var futures = new ArrayList<Future<?>>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  long previousId = 0;
                  long previousTimestamp = 0;
                  for (int i = 0; i < perThread; i++) {
                    Transaction tx = registry.create();
                    assertTrue(tx.id().value() > previousId);
                    assertTrue(tx.timestamp() > previousTimestamp);
                    previousId = tx.id().value();
                    previousTimestamp = tx.timestamp();
                    ids.add(tx.id().value());
                    timestamps.add(tx.timestamp());
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(threads * perThread, ids.size());
    assertEquals(threads * perThread, timestamps.size());
    assertEquals(threads * perThread, registry.activeCount());
  }

  @Test
  void activeCountFollowsFinishedTransactions() {
    var registry = new TransactionRegistry();
    Transaction first = registry.create();
    Transaction second = registry.create();
    assertEquals(List.of(first.id(), second.id()), registry.activeTransactions());

    first.transition(TransactionStatus.PARTIALLY_COMMITTED);
    first.transition(TransactionStatus.COMMITTED);
    registry.markFinished(first);

    assertEquals(1, registry.activeCount());
    assertEquals(List.of(second.id()), registry.activeTransactions());
    assertEquals(Optional.empty(), registry.find(first.id()));
    assertEquals(TransactionStatus.COMMITTED, registry.status(first.id()));
  }

  @Test
  void sustainedAutocommitRetiresTransactionsAndCoalescesFinalStatuses() throws Exception {
    var registry = new TransactionRegistry();
    TransactionId first = null;
    for (int i = 0; i < 1_000; i++) {
      Transaction tx = registry.create();
      if (first == null) first = tx.id();
      tx.transition(TransactionStatus.PARTIALLY_COMMITTED);
      tx.transition(TransactionStatus.COMMITTED);
      registry.markFinished(tx);
      assertTrue(registry.find(tx.id()).isEmpty());
    }

    assertEquals(0, registry.activeCount());
    assertEquals(TransactionStatus.COMMITTED, registry.status(first));
    assertEquals(TransactionStatus.COMMITTED, registry.status(new TransactionId(1_000)));
    var field = TransactionRegistry.class.getDeclaredField("finalized");
    field.setAccessible(true);
    assertEquals(1, ((NavigableMap<?, ?>) field.get(registry)).size());
  }

  @Test
  void outOfOrderFinalizationPreservesEachStatus() {
    var registry = new TransactionRegistry();
    Transaction first = registry.create();
    Transaction second = registry.create();
    Transaction third = registry.create();
    third.transition(TransactionStatus.PARTIALLY_COMMITTED);
    third.transition(TransactionStatus.COMMITTED);
    registry.markFinished(third);
    second.transition(TransactionStatus.FAILED);
    second.transition(TransactionStatus.ABORTED);
    registry.markFinished(second);
    first.transition(TransactionStatus.PARTIALLY_COMMITTED);
    first.transition(TransactionStatus.COMMITTED);
    registry.markFinished(first);

    assertEquals(TransactionStatus.COMMITTED, registry.status(first.id()));
    assertEquals(TransactionStatus.ABORTED, registry.status(second.id()));
    assertEquals(TransactionStatus.COMMITTED, registry.status(third.id()));
  }

  @Test
  void unfinishedTransactionCannotBeMarkedFinished() {
    var registry = new TransactionRegistry();
    Transaction tx = registry.create();
    tx.transition(TransactionStatus.FAILED);

    var error = assertThrows(DbmsException.class, () -> registry.markFinished(tx));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertEquals(1, registry.activeCount());
  }

  @Test
  void unknownTransactionIsReportedWithoutNull() {
    var registry = new TransactionRegistry();
    assertEquals(Optional.empty(), registry.find(new TransactionId(99)));
    var error = assertThrows(DbmsException.class, () -> registry.get(new TransactionId(99)));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
  }

  @Test
  void heldAdmissionRejectsNewTransactionsUntilReleased() {
    var registry = new TransactionRegistry();
    registry.holdAdmission();
    assertTrue(registry.isAdmissionHeld());

    var error = assertThrows(DbmsException.class, registry::create);
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());

    registry.releaseAdmission();
    assertFalse(registry.isAdmissionHeld());
    assertEquals(1, registry.create().id().value());
    assertThrows(DbmsException.class, registry::releaseAdmission);
  }

  @Test
  void awaitNoActiveWakesWhenLastTransactionFinishes() throws Exception {
    var registry = new TransactionRegistry();
    Transaction tx = registry.create();
    assertFalse(registry.awaitNoActive(Duration.ofMillis(20)));

    var finisher =
        new Thread(
            () -> {
              tx.transition(TransactionStatus.FAILED);
              tx.transition(TransactionStatus.ABORTED);
              registry.markFinished(tx);
            });
    finisher.start();

    assertTrue(registry.awaitNoActive(Duration.ofSeconds(5)));
    finisher.join();
  }

  @Test
  void runIfIdleOnlyRunsWithoutActiveTransactions() {
    var registry = new TransactionRegistry();
    var runs = new ArrayList<String>();
    assertTrue(registry.runIfIdle(() -> runs.add("pertama")));

    registry.create();
    assertFalse(registry.runIfIdle(() -> runs.add("kedua")));
    assertEquals(List.of("pertama"), runs);
  }

  @Test
  void restoreHighWaterClearsOldStateAndNeverMovesCountersBack() {
    var registry = new TransactionRegistry();
    Transaction old = registry.create();

    registry.restoreHighWater(
        new RecoveryHighWater(
            new LogSequenceNumber(40), new TransactionId(10), 25, Optional.empty()));

    assertEquals(0, registry.activeCount());
    assertTrue(registry.find(old.id()).isEmpty());
    Transaction next = registry.create();
    assertEquals(11, next.id().value());
    assertEquals(26, next.timestamp());

    registry.restoreHighWater(
        new RecoveryHighWater(new LogSequenceNumber(1), new TransactionId(2), 2, Optional.empty()));
    Transaction later = registry.create();
    assertTrue(later.id().value() > next.id().value());
    assertTrue(later.timestamp() > next.timestamp());
  }
}
