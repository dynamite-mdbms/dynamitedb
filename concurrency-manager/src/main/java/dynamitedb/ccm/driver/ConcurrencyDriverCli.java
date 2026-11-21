package dynamitedb.ccm.driver;

import dynamitedb.ccm.ConcurrencyManagerImpl;
import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.ccm.protocol.Strict2PLProtocol;
import dynamitedb.ccm.recovery.MaintenanceController;
import dynamitedb.ccm.recovery.TransactionRecoveryBridge;
import dynamitedb.ccm.timestamp.Schedule;
import dynamitedb.ccm.timestamp.ScheduledOperation;
import dynamitedb.ccm.timestamp.SerializabilityChecker;
import dynamitedb.ccm.timestamp.StrictWriteIntentTable;
import dynamitedb.ccm.timestamp.TimestampOrderingProtocol;
import dynamitedb.ccm.timestamp.TimestampRegistry;
import dynamitedb.ccm.timestamp.VerificationResult;
import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone schedule runner for the concurrency manager. */
public final class ConcurrencyDriverCli {
  private static final ResourceId A = ResourceId.table(1);
  private static final ResourceId B = ResourceId.table(2);
  private static final ResourceId C = ResourceId.table(3);

  private ConcurrencyDriverCli() {}

  public static void main(String[] args) {
    String selection = args.length == 0 ? "both" : args[0];
    if (args.length > 1 || !List.of("both", "strict-2pl", "strict-timestamp").contains(selection)) {
      System.err.println("Usage: ConcurrencyDriverCli [both|strict-2pl|strict-timestamp]");
      System.exit(2);
    }

    boolean passed = true;
    if (selection.equals("both") || selection.equals("strict-2pl")) {
      passed &= runProtocol(ProtocolType.STRICT_2PL);
    }
    if (selection.equals("both") || selection.equals("strict-timestamp")) {
      passed &= runProtocol(ProtocolType.STRICT_TIMESTAMP);
    }
    if (!passed) System.exit(1);
  }

  private static boolean runProtocol(ProtocolType type) {
    TransactionRegistry registry = new TransactionRegistry();
    MaintenanceController maintenance = new MaintenanceController(registry);
    TransactionWal wal =
        new TransactionWal() {
          @Override
          public void begin(TransactionId tx, long timestamp) {}

          @Override
          public LogSequenceNumber appendChange(dynamitedb.contracts.recovery.ChangeRecord change) {
            return new LogSequenceNumber(1);
          }

          @Override
          public void commitDurably(TransactionId tx) {}
        };
    var locks = new BeforeAwaitLockManager();
    var strict2pl = new Strict2PLProtocol(locks);
    var timestamp =
        new TimestampOrderingProtocol(
            new TimestampRegistry(), new StrictWriteIntentTable(), registry);
    var ccm =
        new ConcurrencyManagerImpl(
            registry,
            Map.of(ProtocolType.STRICT_2PL, strict2pl, ProtocolType.STRICT_TIMESTAMP, timestamp),
            type,
            new TransactionRecoveryBridge(wal, ignored -> {}),
            maintenance,
            ignored -> {});
    ccm.initializeRecoveredState(
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty()));
    var verifier = new SerializabilityChecker();
    System.out.println("=== " + type.displayName() + " ===");
    boolean passed = true;
    passed &=
        run(
            ccm,
            verifier,
            "shared-resource conflict",
            schedule(
                op(1, A, AccessMode.WRITE), op(2, B, AccessMode.WRITE), op(2, A, AccessMode.READ)),
            0,
            A);
    if (type == ProtocolType.STRICT_2PL) {
      passed &=
          run(
              ccm,
              verifier,
              "two-transaction deadlock",
              schedule(
                  op(1, A, AccessMode.WRITE),
                  op(1, B, AccessMode.WRITE),
                  op(2, B, AccessMode.WRITE),
                  op(2, A, AccessMode.WRITE)),
              1,
              null);
      passed &=
          run(
              ccm,
              verifier,
              "three-transaction deadlock",
              schedule(
                  op(1, A, AccessMode.WRITE),
                  op(1, B, AccessMode.WRITE),
                  op(2, B, AccessMode.WRITE),
                  op(2, C, AccessMode.WRITE),
                  op(3, C, AccessMode.WRITE),
                  op(3, A, AccessMode.WRITE)),
              1,
              null);
      passed &=
          run(
              ccm,
              verifier,
              "shared-to-exclusive upgrade deadlock",
              schedule(
                  op(1, A, AccessMode.READ),
                  op(1, A, AccessMode.WRITE),
                  op(2, A, AccessMode.READ),
                  op(2, A, AccessMode.WRITE)),
              1,
              null);
      passed &= verifyAbortBeforeAwait(ccm, locks);
    }
    passed &= verifyManual(verifier);
    return passed;
  }

  private static boolean verifyAbortBeforeAwait(
      ConcurrencyManagerImpl ccm, BeforeAwaitLockManager locks) {
    String name = "deadlock victim aborted before ticket claim";
    System.out.println("--- " + name + " ---");
    TransactionId survivor = ccm.begin();
    TransactionId victim = ccm.begin();
    try {
      ccm.requestAccess(new AccessRequest(survivor, A, AccessMode.WRITE));
      ccm.requestAccess(new AccessRequest(victim, B, AccessMode.WRITE));
      WaitToken victimTicket =
          ccm.requestAccess(new AccessRequest(victim, A, AccessMode.WRITE)).ticket().orElseThrow();
      verifyInvalidTicket(ccm, new WaitToken(survivor, victimTicket.requestId()));

      // Close the cycle after CCM's active check but before the real lock manager claims the
      // ticket.
      locks.beforeAwait =
          () -> {
            WaitToken survivorTicket =
                ccm.requestAccess(new AccessRequest(survivor, B, AccessMode.WRITE))
                    .ticket()
                    .orElseThrow();
            if (ccm.status(victim) != TransactionStatus.ABORTED) {
              throw new IllegalStateException("Deadlock did not abort the younger transaction");
            }
            if (ccm.awaitAccess(survivorTicket).state() != AccessState.GRANTED) {
              throw new IllegalStateException("Expected survivor GRANTED");
            }
            verifyInvalidTicket(ccm, survivorTicket);
          };
      if (ccm.awaitAccess(victimTicket).state() != AccessState.ABORTED) {
        throw new IllegalStateException("Expected victim ABORTED");
      }
      ccm.commit(survivor);
      System.out.println(
          "PASS: " + name + "; victim ABORTED, survivor GRANTED, invalid tickets rejected");
      return true;
    } catch (RuntimeException failure) {
      System.out.println("FAIL: " + name + "; " + failure);
      return false;
    } finally {
      locks.beforeAwait = null;
      if (ccm.status(victim) == TransactionStatus.ACTIVE) ccm.abort(victim);
      if (ccm.status(survivor) == TransactionStatus.ACTIVE) ccm.abort(survivor);
    }
  }

  private static void verifyInvalidTicket(ConcurrencyManagerImpl ccm, WaitToken ticket) {
    try {
      ccm.awaitAccess(ticket);
    } catch (DbmsException failure) {
      if (failure.getCode() == ErrorCode.INVALID_STATE) return;
      throw failure;
    }
    throw new IllegalStateException(
        "Expected INVALID_STATE for active transaction's invalid ticket");
  }

  private static final class BeforeAwaitLockManager extends LockManager {
    private Runnable beforeAwait;

    @Override
    public AccessDecision awaitGrant(WaitToken ticket) {
      Runnable action = beforeAwait;
      if (action != null) {
        beforeAwait = null;
        action.run();
      }
      return super.awaitGrant(ticket);
    }
  }

  private static boolean run(
      ConcurrencyManagerImpl ccm,
      SerializabilityChecker verifier,
      String name,
      Schedule schedule,
      int expectedAborts,
      ResourceId conflictGate) {
    int transactions =
        (int) schedule.operations().stream().map(ScheduledOperation::tx).distinct().count();
    AccessController access = new InitialLockBarrier(ccm, transactions, conflictGate);
    ConcurrencyTestDriver driver = new ConcurrencyTestDriver(access, ccm, verifier::verify);
    TestReport report = driver.run(schedule, expectedAborts, true);
    System.out.println("--- " + name + " ---");
    for (TestResult result : report.results()) {
      System.out.println((result.passed() ? "PASS" : "FAIL") + ": " + result.message());
    }
    System.out.println((report.passed() ? "PASS" : "FAIL") + ": " + name);
    return report.passed();
  }

  private static boolean verifyManual(SerializabilityChecker verifier) {
    Schedule serial = schedule(op(101, A, AccessMode.WRITE), op(102, A, AccessMode.READ));
    Schedule cyclic =
        schedule(
            op(101, A, AccessMode.WRITE),
            op(102, B, AccessMode.WRITE),
            op(101, B, AccessMode.WRITE),
            op(102, A, AccessMode.WRITE));
    VerificationResult serialResult = verifier.verify(serial);
    VerificationResult cyclicResult = verifier.verify(cyclic);
    boolean passed = serialResult.serializable() && !cyclicResult.serializable();
    System.out.println("--- manual serializability verification ---");
    System.out.println(
        (serialResult.serializable() ? "PASS" : "FAIL") + ": serial schedule -> " + serialResult);
    System.out.println(
        (!cyclicResult.serializable() ? "PASS" : "FAIL") + ": cyclic schedule -> " + cyclicResult);
    return passed;
  }

  private static ScheduledOperation op(long tx, ResourceId resource, AccessMode mode) {
    return new ScheduledOperation(new TransactionId(tx), resource, mode);
  }

  private static Schedule schedule(ScheduledOperation... operations) {
    return new Schedule(List.of(operations));
  }

  private static final class InitialLockBarrier implements AccessController {
    private final AccessController delegate;
    private final CountDownLatch granted;
    private final CountDownLatch conflictObserved = new CountDownLatch(1);
    private final ResourceId conflictGate;
    private final Set<TransactionId> firstRequests = ConcurrentHashMap.newKeySet();
    private volatile TransactionId holder;

    private InitialLockBarrier(
        AccessController delegate, int transactions, ResourceId conflictGate) {
      this.delegate = delegate;
      this.conflictGate = conflictGate;
      granted = new CountDownLatch(transactions);
    }

    @Override
    public AccessDecision requestAccess(AccessRequest request) {
      AccessDecision decision = delegate.requestAccess(request);
      if (firstRequests.add(request.tx()) && decision.state() == AccessState.GRANTED) {
        if (request.resource().equals(conflictGate)) holder = request.tx();
        granted.countDown();
        try {
          if (!granted.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Transactions did not acquire their initial locks");
          }
          if (request.tx().equals(holder)
              && conflictGate != null
              && !conflictObserved.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Conflicting request did not reach WAITING");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Initial lock barrier interrupted", interrupted);
        }
      } else if (conflictGate != null
          && !request.tx().equals(holder)
          && request.resource().equals(conflictGate)
          && decision.state() == AccessState.WAITING) {
        conflictObserved.countDown();
      }
      return decision;
    }

    @Override
    public AccessDecision awaitAccess(WaitToken ticket) {
      return delegate.awaitAccess(ticket);
    }
  }
}
