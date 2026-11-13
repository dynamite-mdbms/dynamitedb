package dynamitedb.ccm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.driver.ConcurrencyTestDriver;
import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
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
import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimestampEndToEndTest {
  private static final long SHARED_RESOURCE = 5;
  private static final long HOLDER_RESOURCE = 6;

  @Test
  void newerWriterWaitsForOlderWriterThenProceeds() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    TransactionRegistry registry = new TransactionRegistry();
    var timestamp =
        new TimestampOrderingProtocol(
            new TimestampRegistry(), new StrictWriteIntentTable(), registry);
    Map<ProtocolType, ConcurrencyProtocol> protocols = new EnumMap<>(ProtocolType.class);
    protocols.put(ProtocolType.STRICT_2PL, new Strict2PLProtocol(new LockManager()));
    protocols.put(ProtocolType.STRICT_TIMESTAMP, timestamp);
    FakeRecovery recovery = new FakeRecovery(events);
    var ccm =
        new ConcurrencyManagerImpl(
            registry,
            protocols,
            ProtocolType.STRICT_2PL,
            new TransactionRecoveryBridge(recovery, recovery),
            new MaintenanceController(registry),
            ignored -> {});
    ccm.initializeRecoveredState(
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty()));
    ccm.setProtocol(ProtocolType.STRICT_TIMESTAMP);

    GatingAccessController gate = new GatingAccessController(ccm, registry);
    ConcurrencyTestDriver driver =
        new ConcurrencyTestDriver(gate, ccm, new SerializabilityChecker()::verify);

    TestReport report =
        driver.run(
            new Schedule(
                List.of(
                    operation(1, SHARED_RESOURCE, AccessMode.WRITE),
                    operation(1, HOLDER_RESOURCE, AccessMode.READ),
                    operation(2, SHARED_RESOURCE, AccessMode.WRITE))),
            0,
            true);

    assertTrue(report.passed(), "results: " + report.results());
    assertEquals(TransactionStatus.COMMITTED, ccm.status(new TransactionId(1)));
    assertEquals(TransactionStatus.COMMITTED, ccm.status(new TransactionId(2)));
    assertEquals(0, registry.activeCount());
    assertTrue(
        report.results().stream().anyMatch(result -> result.message().contains("WAITING -> GRANTED")),
        "newer write harus menunggu lalu di-grant: " + report.results());
  }

  private static ScheduledOperation operation(long tx, long tableId, AccessMode mode) {
    return new ScheduledOperation(new TransactionId(tx), ResourceId.table(tableId), mode);
  }

  private final class GatingAccessController implements AccessController {
    private final AccessController delegate;
    private final TransactionRegistry registry;
    private final CountDownLatch olderWriteGranted = new CountDownLatch(1);
    private final CountDownLatch newerWriteResolved = new CountDownLatch(1);

    GatingAccessController(AccessController delegate, TransactionRegistry registry) {
      this.delegate = delegate;
      this.registry = registry;
    }

    @Override
    public AccessDecision requestAccess(AccessRequest request) {
      boolean sharedWrite =
          request.resource().tableId() == SHARED_RESOURCE && request.mode() == AccessMode.WRITE;
      boolean holderOp = request.resource().tableId() == HOLDER_RESOURCE;
      TransactionId[] pair = olderNewer();
      TransactionId older = pair[0];
      TransactionId newer = pair[1];
      if (sharedWrite && request.tx().equals(newer)) {
        await(olderWriteGranted);
        AccessDecision decision = delegate.requestAccess(request);
        if (decision.state() == AccessState.WAITING || decision.state() == AccessState.ABORTED) {
          newerWriteResolved.countDown();
        }
        return decision;
      }
      if (holderOp && request.tx().equals(older)) {
        await(newerWriteResolved);
      }
      AccessDecision decision = delegate.requestAccess(request);
      if (sharedWrite && request.tx().equals(older) && decision.state() == AccessState.GRANTED) {
        olderWriteGranted.countDown();
      }
      return decision;
    }

    @Override
    public AccessDecision awaitAccess(WaitToken ticket) {
      return delegate.awaitAccess(ticket);
    }

    private TransactionId[] olderNewer() {
      List<TransactionId> active = registry.activeTransactions();
      TransactionId older =
          active.stream()
              .min(Comparator.comparingLong(t -> registry.get(t).timestamp()))
              .orElseThrow();
      TransactionId newer =
          active.stream()
              .max(Comparator.comparingLong(t -> registry.get(t).timestamp()))
              .orElseThrow();
      return new TransactionId[] {older, newer};
    }

    private void await(CountDownLatch latch) {
      try {
        if (!latch.await(4, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timestamp e2e schedule deadlocked on a barrier");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Timestamp e2e schedule was interrupted", interrupted);
      }
    }
  }
}
