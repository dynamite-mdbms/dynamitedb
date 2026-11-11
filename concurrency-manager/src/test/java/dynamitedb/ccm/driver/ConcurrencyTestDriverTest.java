package dynamitedb.ccm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.timestamp.Schedule;
import dynamitedb.ccm.timestamp.ScheduledOperation;
import dynamitedb.ccm.timestamp.VerificationResult;
import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConcurrencyTestDriverTest {
  @Test
  void runsTransactionsConcurrentlyAndCommitsThem() {
    TransactionId first = tx(1);
    TransactionId second = tx(2);
    CountDownLatch requestsStarted = new CountDownLatch(2);
    TestTransactions transactions = new TestTransactions();
    AtomicReference<Schedule> checkedSchedule = new AtomicReference<>();
    AccessController access =
        new AccessController() {
          @Override
          public AccessDecision requestAccess(AccessRequest request) {
            requestsStarted.countDown();
            try {
              assertTrue(requestsStarted.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError(interrupted);
            }
            return AccessDecision.granted();
          }

          @Override
          public AccessDecision awaitAccess(WaitToken ticket) {
            return AccessDecision.granted();
          }
        };
    ConcurrencyTestDriver driver =
        new ConcurrencyTestDriver(
            access,
            transactions,
            schedule -> {
              checkedSchedule.set(schedule);
              return new VerificationResult(true, List.of(first, second));
            });

    var report = driver.run(schedule(first, second));

    assertTrue(report.passed());
    assertEquals(TransactionStatus.COMMITTED, transactions.status(first));
    assertEquals(TransactionStatus.COMMITTED, transactions.status(second));
    assertEquals(2, checkedSchedule.get().operations().size());
    assertTrue(report.results().stream().anyMatch(r -> r.message().contains("final schedule")));
  }

  @Test
  void reportsWaitingThenGrantedAndAcceptsExpectedDeadlockAbort() {
    TransactionId first = tx(1);
    TransactionId second = tx(2);
    TestTransactions transactions = new TestTransactions();
    WaitToken ticket = new WaitToken(first, UUID.randomUUID());
    AccessController access =
        new AccessController() {
          @Override
          public AccessDecision requestAccess(AccessRequest request) {
            if (request.tx().equals(first)) {
              return AccessDecision.waiting(ticket, "waiting");
            }
            transactions.abort(request.tx());
            return AccessDecision.aborted("deadlock victim");
          }

          @Override
          public AccessDecision awaitAccess(WaitToken waitToken) {
            assertEquals(ticket, waitToken);
            return AccessDecision.granted();
          }
        };
    ConcurrencyTestDriver driver =
        new ConcurrencyTestDriver(
            access, transactions, schedule -> new VerificationResult(true, List.of(first)));

    var report = driver.run(schedule(first, second), 1, true);

    assertTrue(report.passed());
    assertEquals(TransactionStatus.COMMITTED, transactions.status(first));
    assertEquals(TransactionStatus.ABORTED, transactions.status(second));
    assertTrue(report.results().stream().anyMatch(r -> r.message().contains("WAITING -> GRANTED")));
    assertTrue(report.results().stream().anyMatch(r -> r.message().contains("ABORTED")));
  }

  @Test
  void commitsAnOwnerBeforeWaitingTransactionsFinish() {
    TransactionId first = tx(11);
    TransactionId second = tx(12);
    TestTransactions transactions = new TestTransactions();
    WaitToken ticket = new WaitToken(tx(2), UUID.randomUUID());
    AccessController access =
        new AccessController() {
          @Override
          public AccessDecision requestAccess(AccessRequest request) {
            return request.tx().equals(tx(1))
                ? AccessDecision.granted()
                : AccessDecision.waiting(ticket, "waiting for owner");
          }

          @Override
          public AccessDecision awaitAccess(WaitToken waitToken) {
            try {
              if (transactions.firstCommitted.await(1, TimeUnit.SECONDS)) {
                return AccessDecision.granted();
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return AccessDecision.aborted("interrupted");
            }
            transactions.abort(waitToken.tx());
            return AccessDecision.aborted("owner was not committed");
          }
        };
    ConcurrencyTestDriver driver =
        new ConcurrencyTestDriver(
            access, transactions, schedule -> new VerificationResult(true, List.of(tx(1), tx(2))));

    var report = driver.run(writeSchedule(first, second));

    assertTrue(report.passed());
    assertEquals(TransactionStatus.COMMITTED, transactions.status(tx(1)));
    assertEquals(TransactionStatus.COMMITTED, transactions.status(tx(2)));
    assertTrue(report.results().stream().anyMatch(r -> r.message().contains("WAITING -> GRANTED")));
  }

  @Test
  void reportsOperationsSkippedAfterTransactionAbort() {
    TransactionId victim = tx(12);
    TestTransactions transactions = new TestTransactions();
    AccessController access =
        new AccessController() {
          @Override
          public AccessDecision requestAccess(AccessRequest request) {
            if (request.tx().equals(tx(2))) {
              transactions.abort(request.tx());
              return AccessDecision.aborted("test victim");
            }
            return AccessDecision.granted();
          }

          @Override
          public AccessDecision awaitAccess(WaitToken ticket) {
            return AccessDecision.granted();
          }
        };
    Schedule schedule =
        new Schedule(
            List.of(
                operation(tx(11), AccessMode.READ),
                operation(victim, AccessMode.WRITE),
                operation(victim, AccessMode.READ)));
    ConcurrencyTestDriver driver =
        new ConcurrencyTestDriver(
            access, transactions, ignored -> new VerificationResult(true, List.of(tx(1))));

    var report = driver.run(schedule, 1, true);

    assertTrue(report.passed());
    assertTrue(
        report.results().stream()
            .anyMatch(r -> r.message().contains("not executed after transaction abort")));
  }

  @Test
  void waitsForCancelledWorkersBeforeReturningFailureReport() {
    CountDownLatch secondEntered = new CountDownLatch(1);
    CountDownLatch secondFinished = new CountDownLatch(1);
    TestTransactions transactions = new TestTransactions();
    AccessController access =
        new AccessController() {
          @Override
          public AccessDecision requestAccess(AccessRequest request) {
            if (request.tx().equals(tx(1))) {
              try {
                assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
              throw new IllegalStateException("deliberate test failure");
            }
            secondEntered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
              long finishAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
              while (System.nanoTime() < finishAt) {
                try {
                  TimeUnit.NANOSECONDS.sleep(finishAt - System.nanoTime());
                } catch (InterruptedException ignored) {
                  // Keep the worker alive briefly so the test can verify cleanup waits for it.
                }
              }
              secondFinished.countDown();
              return AccessDecision.aborted("cancelled");
            }
            throw new AssertionError("unreachable");
          }

          @Override
          public AccessDecision awaitAccess(WaitToken ticket) {
            return AccessDecision.granted();
          }
        };
    ConcurrencyTestDriver driver =
        new ConcurrencyTestDriver(
            access, transactions, ignored -> new VerificationResult(true, List.of()));

    var report = driver.run(schedule(tx(11), tx(12)));

    assertTrue(!report.passed());
    assertEquals(0, secondFinished.getCount());
  }

  private static Schedule schedule(TransactionId... txs) {
    return new Schedule(
        java.util.Arrays.stream(txs)
            .map(tx -> new ScheduledOperation(tx, ResourceId.table(7), AccessMode.READ))
            .toList());
  }

  private static Schedule writeSchedule(TransactionId... txs) {
    return new Schedule(
        java.util.Arrays.stream(txs)
            .map(tx -> new ScheduledOperation(tx, ResourceId.table(7), AccessMode.WRITE))
            .toList());
  }

  private static ScheduledOperation operation(TransactionId tx, AccessMode mode) {
    return new ScheduledOperation(tx, ResourceId.table(7), mode);
  }

  private static TransactionId tx(long id) {
    return new TransactionId(id);
  }

  private static final class TestTransactions implements TransactionManager {
    private final Map<TransactionId, TransactionStatus> statuses = new ConcurrentHashMap<>();
    private final CountDownLatch firstCommitted = new CountDownLatch(1);
    private long nextTransactionId;

    @Override
    public TransactionId begin() {
      TransactionId tx = new TransactionId(++nextTransactionId);
      statuses.put(tx, TransactionStatus.ACTIVE);
      return tx;
    }

    @Override
    public void commit(TransactionId tx) {
      statuses.put(tx, TransactionStatus.COMMITTED);
      if (tx.equals(tx(1))) firstCommitted.countDown();
    }

    @Override
    public void abort(TransactionId tx) {
      statuses.put(tx, TransactionStatus.ABORTED);
    }

    @Override
    public TransactionStatus status(TransactionId tx) {
      return statuses.get(tx);
    }
  }
}
