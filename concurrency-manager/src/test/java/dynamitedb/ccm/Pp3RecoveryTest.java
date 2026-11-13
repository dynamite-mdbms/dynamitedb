package dynamitedb.ccm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.ccm.protocol.Strict2PLProtocol;
import dynamitedb.ccm.recovery.MaintenanceController;
import dynamitedb.ccm.recovery.TransactionRecoveryBridge;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Pp3RecoveryTest {
  private final List<String> events = new ArrayList<>();
  private final TransactionRegistry registry = new TransactionRegistry();
  private final LockManager locks = new LockManager();
  private final FakeRecovery recovery = new FakeRecovery(events);
  private final ConcurrencyManagerImpl ccm =
      new ConcurrencyManagerImpl(
          registry,
          new Strict2PLProtocol(locks),
          new TransactionRecoveryBridge(recovery, recovery),
          new MaintenanceController(registry));

  private static RecoveryHighWater highWater(long id, long timestamp) {
    return new RecoveryHighWater(
        new LogSequenceNumber(100), new TransactionId(id), timestamp, Optional.empty());
  }

  private void ready() {
    ccm.initializeRecoveredState(highWater(0, 0));
  }

  private static AccessRequest access(TransactionId tx, AccessMode mode) {
    return new AccessRequest(tx, ResourceId.table(7), mode);
  }

  private static PreparedWrite write(TransactionId tx) {
    return new PreparedWrite(
        tx,
        "students",
        new RecordId(7, 1, 0, 1),
        RecordImage.absent(),
        new RecordImage(true, ImmutableBytes.copyOf(new byte[] {42})),
        new WriteReservationId(UUID.randomUUID()));
  }

  @Test
  void writesAreLoggedAfterPermissionWhileReadsDoNotGenerateChanges() {
    ready();
    TransactionId tx = ccm.begin();
    ccm.requestAccess(access(tx, AccessMode.READ));
    assertEquals(List.of("wal begin T1 ts 1"), events);
    ccm.requestAccess(access(tx, AccessMode.WRITE));
    assertEquals(List.of("wal begin T1 ts 1"), events);

    var prepared = write(tx);
    assertEquals(new LogSequenceNumber(1), ccm.recordChange(tx, prepared));
    assertEquals(prepared.rid(), recovery.lastChange.rid());
    ccm.commit(tx);

    assertEquals(List.of("wal begin T1 ts 1", "wal change T1", "wal commit T1"), events);
    assertEquals(0, registry.activeCount());
  }

  @Test
  void appendFailureAbortsBeforeReturningAndRetainsProtectionIfRollbackFails() {
    ready();
    TransactionId writer = ccm.begin();
    TransactionId reader = ccm.begin();
    ccm.requestAccess(access(writer, AccessMode.WRITE));
    var waiting = ccm.requestAccess(access(reader, AccessMode.READ)).ticket().orElseThrow();
    recovery.failChange = true;
    recovery.failRollback = true;

    var failure = assertThrows(DbmsException.class, () -> ccm.recordChange(writer, write(writer)));

    assertEquals(ErrorCode.RECOVERY_FAILURE, failure.getCode());
    assertEquals(ErrorCode.WAL_FAILURE, ((DbmsException) failure.getSuppressed()[0]).getCode());
    assertEquals(TransactionStatus.FAILED, ccm.status(writer));
    assertEquals(AccessState.WAITING, ccm.requestAccess(access(reader, AccessMode.READ)).state());
    assertThrows(DbmsException.class, () -> ccm.commit(writer));

    recovery.failRollback = false;
    ccm.abort(writer);
    assertEquals(AccessState.GRANTED, ccm.awaitAccess(waiting).state());
    assertEquals(TransactionStatus.ABORTED, ccm.status(writer));
    ccm.commit(reader);
  }

  @Test
  void appendFailureWithSuccessfulRollbackReportsWalFailure() {
    ready();
    TransactionId tx = ccm.begin();
    recovery.failChange = true;

    var failure = assertThrows(DbmsException.class, () -> ccm.recordChange(tx, write(tx)));

    assertEquals(ErrorCode.WAL_FAILURE, failure.getCode());
    assertEquals(TransactionStatus.ABORTED, ccm.status(tx));
    assertEquals(0, registry.activeCount());
    assertEquals(List.of("wal begin T1 ts 1", "rollback T1"), events);
  }

  @Test
  void rollbackRestoresMockDataBeforeAnotherTransactionCanRead() {
    ready();
    TransactionId writer = ccm.begin();
    TransactionId reader = ccm.begin();
    ccm.requestAccess(access(writer, AccessMode.WRITE));
    ccm.recordChange(writer, write(writer));
    var data = new AtomicInteger(42);
    var waiting = ccm.requestAccess(access(reader, AccessMode.READ)).ticket().orElseThrow();
    recovery.duringRollback =
        () -> {
          assertEquals(TransactionStatus.FAILED, ccm.status(writer));
          assertEquals(
              AccessState.WAITING, ccm.requestAccess(access(reader, AccessMode.READ)).state());
          data.set(0);
        };

    ccm.abort(writer);
    ccm.abort(writer);

    assertEquals(AccessState.GRANTED, ccm.awaitAccess(waiting).state());
    assertEquals(0, data.get());
    assertEquals(1, events.stream().filter(event -> event.equals("rollback T1")).count());
    ccm.commit(reader);
  }

  @Test
  void refusesLoggingForUnknownFinalAndForeignTransactions() {
    ready();
    TransactionId committed = ccm.begin();
    ccm.commit(committed);
    TransactionId aborted = ccm.begin();
    ccm.abort(aborted);
    TransactionId active = ccm.begin();
    events.clear();

    for (TransactionId tx : List.of(committed, aborted, new TransactionId(99))) {
      assertThrows(DbmsException.class, () -> ccm.recordChange(tx, write(tx)));
    }
    assertThrows(DbmsException.class, () -> ccm.recordChange(active, write(committed)));
    assertEquals(TransactionStatus.ACTIVE, ccm.status(active));
    assertTrue(events.isEmpty());
    assertThrows(DbmsException.class, () -> ccm.transactionTimestamp(new TransactionId(99)));
  }

  @Test
  void startupWaitsForRecoveryAndAllocatesBeyondRecoveredHighWater() throws Exception {
    var started = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<TransactionId> pending =
          executor.submit(
              () -> {
                started.countDown();
                return ccm.begin();
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
      assertTrue(events.isEmpty());

      ccm.initializeRecoveredState(highWater(80, 140));
      TransactionId tx = pending.get(5, TimeUnit.SECONDS);

      assertEquals(new TransactionId(81), tx);
      assertEquals(141, ccm.transactionTimestamp(tx));
      assertEquals(List.of("wal begin T81 ts 141"), events);
    }
  }

  @Test
  void initializationClearsStaleLocksWaitersAndTransactionState() {
    var oldRegistry = new TransactionRegistry();
    var oldLocks = new LockManager();
    var oldWriter = oldRegistry.create();
    var oldReader = oldRegistry.create();
    oldLocks.acquire(access(oldWriter.id(), AccessMode.WRITE));
    var staleTicket =
        oldLocks.acquire(access(oldReader.id(), AccessMode.READ)).ticket().orElseThrow();
    var restored =
        new ConcurrencyManagerImpl(
            oldRegistry,
            new Strict2PLProtocol(oldLocks),
            new TransactionRecoveryBridge(recovery, recovery),
            new MaintenanceController(oldRegistry));

    restored.initializeRecoveredState(highWater(40, 60));

    assertTrue(oldRegistry.find(oldWriter.id()).isEmpty());
    assertTrue(oldRegistry.find(oldReader.id()).isEmpty());
    assertThrows(DbmsException.class, () -> oldLocks.awaitGrant(staleTicket));
    TransactionId next = restored.begin();
    assertEquals(new TransactionId(41), next);
    assertEquals(
        AccessState.GRANTED, restored.requestAccess(access(next, AccessMode.WRITE)).state());
    restored.commit(next);
    assertTrue(oldLocks.waitForGraph().edges().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, Long.MAX_VALUE})
  void invalidHighWaterKeepsStartupClosedUntilRecoveryIsRetried(long invalid) throws Exception {
    assertThrows(DbmsException.class, () -> ccm.initializeRecoveredState(highWater(invalid, 1)));
    var started = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<TransactionId> pending =
          executor.submit(
              () -> {
                started.countDown();
                return ccm.begin();
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(DbmsException.class, () -> ccm.initializeRecoveredState(highWater(1, invalid)));
      assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));

      ready();
      assertEquals(new TransactionId(1), pending.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void runtimeReinitializationRequiresMaintenanceAndNeverMovesCountersBackward() throws Exception {
    ready();
    TransactionId tx = ccm.begin();
    assertThrows(DbmsException.class, () -> ccm.initializeRecoveredState(highWater(0, 0)));
    assertEquals(TransactionStatus.ACTIVE, ccm.status(tx));
    ccm.commit(tx);
    var guard = ccm.acquireMaintenance();
    var started = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<TransactionId> pending =
          executor.submit(
              () -> {
                started.countDown();
                return ccm.begin();
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertTrue(registry.isAdmissionHeld());
      ccm.initializeRecoveredState(highWater(0, 0));
      assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
      guard.close();
      assertFalse(registry.isAdmissionHeld());
      assertEquals(tx.value() + 1, pending.get(5, TimeUnit.SECONDS).value());
    }
    TransactionId next = ccm.begin();
    assertTrue(next.value() > tx.value());
    assertTrue(ccm.transactionTimestamp(next) > 1);
  }
}
