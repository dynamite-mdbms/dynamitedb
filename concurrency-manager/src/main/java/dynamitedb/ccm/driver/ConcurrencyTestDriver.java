package dynamitedb.ccm.driver;

import dynamitedb.ccm.timestamp.Schedule;
import dynamitedb.ccm.timestamp.ScheduledOperation;
import dynamitedb.ccm.timestamp.VerificationResult;
import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class ConcurrencyTestDriver {
  private static final long SCENARIO_TIMEOUT_SECONDS = 5;

  private final AccessController access;
  private final TransactionManager transactions;
  private final Function<Schedule, VerificationResult> verifier;

  public ConcurrencyTestDriver(
      AccessController access,
      TransactionManager transactions,
      Function<Schedule, VerificationResult> verifier) {
    this.access = Objects.requireNonNull(access, "access");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  public TestReport run(Schedule schedule) {
    return run(schedule, 0, true);
  }

  public TestReport run(Schedule schedule, int expectedAborts, boolean expectedSerializable) {
    Objects.requireNonNull(schedule, "schedule");
    if (expectedAborts < 0) {
      throw new IllegalArgumentException("expectedAborts must not be negative");
    }

    List<TestResult> results = new ArrayList<>();
    List<TestResult> operationResults = new ArrayList<>();
    List<ScheduledOperation> grantedOperations = new ArrayList<>();
    Map<TransactionId, List<ScheduledOperation>> byTransaction;
    try {
      byTransaction = groupAndBegin(schedule, results);
    } catch (RuntimeException failure) {
      results.add(new TestResult(false, "Could not start schedule: " + failure.getMessage()));
      return new TestReport(results);
    }
    ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, byTransaction.size()));
    CountDownLatch ready = new CountDownLatch(byTransaction.size());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> tasks = new ArrayList<>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SCENARIO_TIMEOUT_SECONDS);

    for (Map.Entry<TransactionId, List<ScheduledOperation>> entry : byTransaction.entrySet()) {
      tasks.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                List<ScheduledOperation> operations = entry.getValue();
                int operationIndex = 0;
                for (; operationIndex < operations.size(); operationIndex++) {
                  if (transactions.status(entry.getKey()) != TransactionStatus.ACTIVE) break;
                  if (!execute(
                      operations.get(operationIndex), operationResults, grantedOperations)) {
                    if (transactions.status(entry.getKey()) == TransactionStatus.ACTIVE) {
                      transactions.abort(entry.getKey());
                    }
                    operationIndex++;
                    break;
                  }
                }
                if (operationIndex < operations.size()) {
                  reportSkipped(
                      operations.subList(operationIndex, operations.size()),
                      transactions.status(entry.getKey()),
                      operationResults);
                }
                if (transactions.status(entry.getKey()) == TransactionStatus.ACTIVE) {
                  transactions.commit(entry.getKey());
                }
                return null;
              }));
    }

    boolean executionSucceeded = false;
    try {
      if (!ready.await(SCENARIO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new TimeoutException("Transactions did not reach the start barrier");
      }
      start.countDown();
      for (Future<?> task : tasks) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Schedule exceeded five seconds");
        task.get(remaining, TimeUnit.NANOSECONDS);
      }
      executionSucceeded = true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      results.add(new TestResult(false, "Schedule interrupted"));
    } catch (ExecutionException | TimeoutException failure) {
      results.add(new TestResult(false, "Schedule execution failed: " + failure.getMessage()));
    } finally {
      start.countDown();
      if (!executionSucceeded) {
        tasks.forEach(task -> task.cancel(true));
        abortActive(byTransaction.keySet(), results);
      }
      executor.shutdownNow();
      try {
        if (!executor.awaitTermination(SCENARIO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          results.add(new TestResult(false, "Schedule workers did not stop within five seconds"));
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        results.add(
            new TestResult(false, "Interrupted while waiting for schedule workers to stop"));
      }
    }

    List<ScheduledOperation> committedOperations =
        grantedOperations.stream()
            .filter(operation -> transactions.status(operation.tx()) == TransactionStatus.COMMITTED)
            .toList();
    Schedule finalSchedule = new Schedule(committedOperations);
    results.addAll(operationResults);
    results.add(new TestResult(true, "final schedule: " + finalSchedule.operations()));

    try {
      VerificationResult verification =
          Objects.requireNonNull(verifier.apply(finalSchedule), "verification result");
      results.add(
          new TestResult(
              verification.serializable() == expectedSerializable,
              "serializable: "
                  + verification.serializable()
                  + "; serial order: "
                  + verification.serialOrder()));
    } catch (RuntimeException failure) {
      results.add(
          new TestResult(false, "Serializability verification failed: " + failure.getMessage()));
    }

    long aborted =
        byTransaction.keySet().stream()
            .filter(tx -> transactions.status(tx) == TransactionStatus.ABORTED)
            .count();
    results.add(
        new TestResult(
            aborted == expectedAborts,
            "aborted transactions: " + aborted + "; expected: " + expectedAborts));
    return new TestReport(results);
  }

  private boolean execute(
      ScheduledOperation operation,
      List<TestResult> operationResults,
      List<ScheduledOperation> grantedOperations) {
    AccessDecision decision =
        access.requestAccess(
            new AccessRequest(operation.tx(), operation.resource(), operation.mode()));
    String status = decision.state().name();
    if (decision.state() == AccessState.WAITING) {
      WaitToken ticket = decision.ticket().orElseThrow();
      decision = access.awaitAccess(ticket);
      status += " -> " + decision.state().name();
    }
    synchronized (operationResults) {
      operationResults.add(
          new TestResult(decision.state() != AccessState.WAITING, operation + " -> " + status));
    }
    if (decision.state() == AccessState.GRANTED) {
      synchronized (grantedOperations) {
        grantedOperations.add(operation);
      }
    }
    return decision.state() == AccessState.GRANTED;
  }

  private static void reportSkipped(
      List<ScheduledOperation> operations,
      TransactionStatus transactionStatus,
      List<TestResult> operationResults) {
    boolean expectedAbort = transactionStatus == TransactionStatus.ABORTED;
    String status =
        expectedAbort
            ? "ABORTED (not executed after transaction abort)"
            : "NOT EXECUTED (transaction status: " + transactionStatus + ")";
    synchronized (operationResults) {
      for (ScheduledOperation operation : operations) {
        operationResults.add(new TestResult(expectedAbort, operation + " -> " + status));
      }
    }
  }

  private void abortActive(Iterable<TransactionId> txs, List<TestResult> results) {
    for (TransactionId tx : txs) {
      if (transactions.status(tx) == TransactionStatus.ACTIVE) {
        try {
          transactions.abort(tx);
        } catch (RuntimeException failure) {
          results.add(
              new TestResult(
                  false, "Cleanup abort failed for " + tx + ": " + failure.getMessage()));
        }
      }
    }
  }

  private Map<TransactionId, List<ScheduledOperation>> groupAndBegin(
      Schedule schedule, List<TestResult> results) {
    Objects.requireNonNull(schedule.operations(), "schedule.operations");
    Map<TransactionId, TransactionId> transactionIds = new LinkedHashMap<>();
    try {
      for (ScheduledOperation operation : schedule.operations()) {
        Objects.requireNonNull(operation, "schedule operation");
        transactionIds.computeIfAbsent(operation.tx(), ignored -> transactions.begin());
      }
    } catch (RuntimeException failure) {
      abortActive(transactionIds.values(), results);
      throw failure;
    }
    Map<TransactionId, List<ScheduledOperation>> grouped = new LinkedHashMap<>();
    for (ScheduledOperation operation : schedule.operations()) {
      TransactionId tx = transactionIds.get(operation.tx());
      ScheduledOperation mapped =
          new ScheduledOperation(tx, operation.resource(), operation.mode());
      grouped.computeIfAbsent(tx, ignored -> new ArrayList<>()).add(mapped);
    }
    grouped.replaceAll((tx, operations) -> List.copyOf(operations));
    return grouped;
  }
}
