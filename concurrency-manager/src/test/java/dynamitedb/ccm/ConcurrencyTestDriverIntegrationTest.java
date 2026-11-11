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
import dynamitedb.ccm.timestamp.StrictWriteIntentTable;
import dynamitedb.ccm.timestamp.TimestampOrderingProtocol;
import dynamitedb.ccm.timestamp.TimestampRegistry;
import dynamitedb.ccm.timestamp.VerificationResult;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrencyTestDriverIntegrationTest {
  @Test
  void runsNonDeadlockScheduleWithBothProtocols() {
    for (ProtocolType type : ProtocolType.values()) {
      List<String> events = Collections.synchronizedList(new ArrayList<>());
      TransactionRegistry registry = new TransactionRegistry();
      var locks = new LockManager();
      var ccm = createManager(registry, locks, new Strict2PLProtocol(locks), events);
      ccm.initializeRecoveredState(highWater());
      ccm.setProtocol(type);
      ConcurrencyTestDriver driver =
          new ConcurrencyTestDriver(
              ccm,
              ccm,
              schedule ->
                  new VerificationResult(
                      true, List.of(new TransactionId(1), new TransactionId(2))));

      var report = driver.run(nonDeadlockSchedule());

      assertTrue(report.passed(), "protocol: " + type + ", results: " + report.results());
      assertEquals(TransactionStatus.COMMITTED, ccm.status(new TransactionId(1)));
      assertEquals(TransactionStatus.COMMITTED, ccm.status(new TransactionId(2)));
      assertEquals(0, registry.activeCount());
    }
  }

  @Test
  void resolvesThreeTransactionDeadlockThroughTheDriverAndCcmLifecycle() {
    int count = 3;
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    TransactionRegistry registry = new TransactionRegistry();
    LockManager locks = new LockManager();
    CountDownLatch allFirstLocksHeld = new CountDownLatch(count);
    ThreadLocal<Boolean> firstRequest = ThreadLocal.withInitial(() -> true);
    Strict2PLProtocol strict2PL =
        new Strict2PLProtocol(locks) {
          @Override
          public dynamitedb.contracts.transaction.AccessDecision request(
              dynamitedb.contracts.transaction.AccessRequest request) {
            var decision = super.request(request);
            if (firstRequest.get()) {
              firstRequest.set(false);
              allFirstLocksHeld.countDown();
              try {
                if (!allFirstLocksHeld.await(2, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Transactions did not acquire their first locks");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Deadlock schedule was interrupted", interrupted);
              }
            }
            return decision;
          }
        };
    var ccm = createManager(registry, locks, strict2PL, events);
    ccm.initializeRecoveredState(highWater());
    ConcurrencyTestDriver driver =
        new ConcurrencyTestDriver(
            ccm,
            ccm,
            schedule ->
                new VerificationResult(
                    true,
                    schedule.operations().stream()
                        .map(ScheduledOperation::tx)
                        .distinct()
                        .toList()));

    var report = driver.run(cyclicWriteSchedule(count), 1, true);

    assertTrue(report.passed(), "results: " + report.results());
    assertEquals(TransactionStatus.COMMITTED, ccm.status(new TransactionId(1)));
    assertEquals(TransactionStatus.COMMITTED, ccm.status(new TransactionId(2)));
    assertEquals(TransactionStatus.ABORTED, ccm.status(new TransactionId(3)));
    assertEquals(0, registry.activeCount());
    assertEquals(1, events.stream().filter(event -> event.equals("rollback T3")).count());
  }

  private static ConcurrencyManagerImpl createManager(
      TransactionRegistry registry,
      LockManager locks,
      Strict2PLProtocol strict2PL,
      List<String> events) {
    FakeRecovery recovery = new FakeRecovery(events);
    var timestamp =
        new TimestampOrderingProtocol(
            new TimestampRegistry(), new StrictWriteIntentTable(), registry);
    Map<ProtocolType, ConcurrencyProtocol> protocols = new EnumMap<>(ProtocolType.class);
    protocols.put(ProtocolType.STRICT_2PL, strict2PL);
    protocols.put(ProtocolType.STRICT_TIMESTAMP, timestamp);
    return new ConcurrencyManagerImpl(
        registry,
        protocols,
        ProtocolType.STRICT_2PL,
        new TransactionRecoveryBridge(recovery, recovery),
        new MaintenanceController(registry),
        ignored -> {});
  }

  private static RecoveryHighWater highWater() {
    return new RecoveryHighWater(
        new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());
  }

  private static Schedule nonDeadlockSchedule() {
    return new Schedule(
        List.of(operation(11, 7, AccessMode.READ), operation(12, 7, AccessMode.READ)));
  }

  private static Schedule cyclicWriteSchedule(int transactionCount) {
    List<ScheduledOperation> operations = new ArrayList<>();
    for (int id = 1; id <= transactionCount; id++) {
      int nextResource = id == transactionCount ? 1 : id + 1;
      operations.add(operation(id, id, AccessMode.WRITE));
      operations.add(operation(id, nextResource, AccessMode.WRITE));
    }
    return new Schedule(operations);
  }

  private static ScheduledOperation operation(long tx, long tableId, AccessMode mode) {
    return new ScheduledOperation(new TransactionId(tx), ResourceId.table(tableId), mode);
  }
}
