package dynamitedb.ccm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.ccm.recovery.MaintenanceController;
import dynamitedb.ccm.recovery.TransactionRecoveryBridge;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConcurrencyManagerImplTest {
  private List<String> events;
  private List<String> announcements;
  private TransactionRegistry registry;
  private FakeProtocol locking;
  private FakeProtocol timestamp;
  private FakeRecovery recovery;
  private ConcurrencyManagerImpl ccm;

  @BeforeEach
  void setUp() {
    events = Collections.synchronizedList(new ArrayList<>());
    announcements = new ArrayList<>();
    registry = new TransactionRegistry();
    locking = new FakeProtocol("2pl", events);
    timestamp = new FakeProtocol("ts", events);
    recovery = new FakeRecovery(events);
    ccm =
        new ConcurrencyManagerImpl(
            registry,
            Map.<ProtocolType, ConcurrencyProtocol>of(
                ProtocolType.STRICT_2PL, locking, ProtocolType.STRICT_TIMESTAMP, timestamp),
            ProtocolType.defaultProtocol(),
            new TransactionRecoveryBridge(recovery, recovery),
            new MaintenanceController(registry),
            announcements::add);
    ccm.initializeRecoveredState(
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty()));
  }

  private static AccessRequest write(TransactionId tx) {
    return new AccessRequest(tx, ResourceId.table(7), AccessMode.WRITE);
  }

  @Test
  void beginStartsActiveTransactionAndWritesBeginRecord() {
    TransactionId first = ccm.begin();
    TransactionId second = ccm.begin();

    assertTrue(second.compareTo(first) > 0);
    assertEquals(TransactionStatus.ACTIVE, ccm.status(first));
    assertEquals(List.of("wal begin T1 ts 1", "wal begin T2 ts 2"), events);
    assertEquals(1, ccm.transactionTimestamp(first));
    assertEquals(2, registry.activeCount());
  }

  @Test
  void beginFailureAbortsTheNewTransaction() {
    recovery.failBegin = true;

    var error = assertThrows(DbmsException.class, ccm::begin);

    assertEquals(ErrorCode.WAL_FAILURE, error.getCode());
    assertEquals(TransactionStatus.ABORTED, ccm.status(new TransactionId(1)));
    assertEquals(0, registry.activeCount());
  }

  @Test
  void commitWritesCommitRecordBeforeReleasingProtocolData() {
    TransactionId tx = ccm.begin();
    assertEquals(AccessState.GRANTED, ccm.requestAccess(write(tx)).state());

    ccm.commit(tx);

    assertEquals(TransactionStatus.COMMITTED, ccm.status(tx));
    assertEquals(
        List.of("wal begin T1 ts 1", "2pl request T1", "wal commit T1", "2pl finish T1"), events);
    assertEquals(0, registry.activeCount());
  }

  @Test
  void committedTransactionIsFinal() {
    TransactionId tx = ccm.begin();
    ccm.commit(tx);

    assertEquals(
        ErrorCode.INVALID_STATE, assertThrows(DbmsException.class, () -> ccm.commit(tx)).getCode());
    assertEquals(
        ErrorCode.INVALID_STATE, assertThrows(DbmsException.class, () -> ccm.abort(tx)).getCode());
    assertEquals(TransactionStatus.COMMITTED, ccm.status(tx));
  }

  @Test
  void commitFailureRollsBackThroughTheAbortFlow() {
    TransactionId tx = ccm.begin();
    recovery.failCommit = true;

    var error = assertThrows(DbmsException.class, () -> ccm.commit(tx));

    assertEquals(ErrorCode.WAL_FAILURE, error.getCode());
    assertEquals(TransactionStatus.ABORTED, ccm.status(tx));
    assertEquals(List.of("wal begin T1 ts 1", "rollback T1", "2pl finish T1"), events);
    assertEquals(0, registry.activeCount());
  }

  @Test
  void abortRollsBackBeforeReleasingProtocolData() {
    TransactionId tx = ccm.begin();

    ccm.abort(tx);
    ccm.abort(tx);

    assertEquals(TransactionStatus.ABORTED, ccm.status(tx));
    assertEquals(List.of("wal begin T1 ts 1", "rollback T1", "2pl finish T1"), events);
    assertEquals(0, registry.activeCount());
  }

  @Test
  void failedRollbackKeepsProtectionUntilRetrySucceeds() {
    TransactionId tx = ccm.begin();
    recovery.failRollback = true;

    var error = assertThrows(DbmsException.class, () -> ccm.abort(tx));

    assertEquals(ErrorCode.RECOVERY_FAILURE, error.getCode());
    assertEquals(TransactionStatus.FAILED, ccm.status(tx));
    assertFalse(events.contains("2pl finish T1"));
    assertEquals(1, registry.activeCount());
    assertEquals(AccessState.ABORTED, ccm.requestAccess(write(tx)).state());

    recovery.failRollback = false;
    ccm.abort(tx);

    assertEquals(TransactionStatus.ABORTED, ccm.status(tx));
    assertTrue(events.contains("2pl finish T1"));
    assertEquals(0, registry.activeCount());
  }

  @Test
  void accessIsOnlyForwardedForActiveTransactions() {
    TransactionId committed = ccm.begin();
    ccm.commit(committed);
    TransactionId aborted = ccm.begin();
    ccm.abort(aborted);
    events.clear();

    AccessDecision afterCommit = ccm.requestAccess(write(committed));
    AccessDecision afterAbort = ccm.requestAccess(write(aborted));
    AccessDecision unknown = ccm.requestAccess(write(new TransactionId(50)));

    assertEquals(AccessState.ABORTED, afterCommit.state());
    assertEquals(AccessState.ABORTED, afterAbort.state());
    assertEquals("Transaksi T2 telah dibatalkan", afterAbort.reason());
    assertEquals(AccessState.ABORTED, unknown.state());
    assertEquals(List.of(), events);
  }

  @Test
  void waitingDecisionIsPassedOnAndAwaitUsesTheTicket() {
    TransactionId tx = ccm.begin();
    locking.answerRequestsWith(request -> FakeProtocol.waitFor(request.tx()));

    AccessDecision first = ccm.requestAccess(write(tx));

    assertEquals(AccessState.WAITING, first.state());
    assertTrue(first.ticket().isPresent());
    assertEquals(AccessState.GRANTED, ccm.awaitAccess(first.ticket().get()).state());
    assertTrue(events.contains("2pl await T1"));
    assertEquals(TransactionStatus.ACTIVE, ccm.status(tx));
  }

  @Test
  void abortedDecisionFromProtocolRunsTheSameAbortFlow() {
    TransactionId tx = ccm.begin();
    locking.answerRequestsWith(request -> AccessDecision.aborted("pelanggaran timestamp"));

    AccessDecision decision = ccm.requestAccess(write(tx));

    assertEquals(AccessState.ABORTED, decision.state());
    assertEquals(TransactionStatus.ABORTED, ccm.status(tx));
    assertEquals(
        List.of("wal begin T1 ts 1", "2pl request T1", "rollback T1", "2pl finish T1"), events);
  }

  @Test
  void abortedWhileWaitingIsResolvedThroughAwaitAccess() {
    TransactionId tx = ccm.begin();
    locking.answerWaitsWith(AccessDecision.aborted("korban deadlock"));

    AccessDecision decision = ccm.awaitAccess(new WaitToken(tx, UUID.randomUUID()));

    assertEquals(AccessState.ABORTED, decision.state());
    assertEquals(TransactionStatus.ABORTED, ccm.status(tx));
    assertEquals(0, registry.activeCount());
  }

  @Test
  void defaultProtocolIsStrictTwoPhaseLockingAndIsAnnounced() {
    assertEquals(ProtocolType.STRICT_2PL, ccm.activeProtocol());

    ccm.setProtocol(ProtocolType.STRICT_2PL);

    assertEquals(
        List.of("Protokol concurrency control aktif: Strict Two Phase Locking"), announcements);
  }

  @Test
  void switchingProtocolRoutesNewTransactionsToTheNewProtocol() {
    ccm.setProtocol(ProtocolType.STRICT_TIMESTAMP);
    TransactionId tx = ccm.begin();
    ccm.requestAccess(write(tx));
    ccm.commit(tx);

    assertEquals(ProtocolType.STRICT_TIMESTAMP, ccm.activeProtocol());
    assertTrue(events.contains("ts request T1"));
    assertTrue(events.contains("ts finish T1"));
    assertFalse(events.stream().anyMatch(event -> event.startsWith("2pl")));
    assertEquals(
        List.of("Protokol concurrency control aktif: Strict Timestamp Ordering"), announcements);
  }

  @Test
  void switchingIsRejectedWhileTransactionsAreActive() {
    TransactionId tx = ccm.begin();

    var error =
        assertThrows(DbmsException.class, () -> ccm.setProtocol(ProtocolType.STRICT_TIMESTAMP));

    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertEquals(ProtocolType.STRICT_2PL, ccm.activeProtocol());

    ccm.abort(tx);
    ccm.setProtocol(ProtocolType.STRICT_TIMESTAMP);
    assertEquals(ProtocolType.STRICT_TIMESTAMP, ccm.activeProtocol());
  }

  @Test
  void switchingIsRejectedWhileRollbackIsStillPending() {
    TransactionId tx = ccm.begin();
    recovery.failRollback = true;
    assertThrows(DbmsException.class, () -> ccm.abort(tx));

    assertThrows(DbmsException.class, () -> ccm.setProtocol(ProtocolType.STRICT_TIMESTAMP));
    assertEquals(ProtocolType.STRICT_2PL, ccm.activeProtocol());
  }

  @Test
  void singleProtocolConstructorReportsMissingAlternative() {
    var single =
        new ConcurrencyManagerImpl(
            registry,
            locking,
            new TransactionRecoveryBridge(recovery, recovery),
            new MaintenanceController(registry));

    assertEquals(ProtocolType.STRICT_2PL, single.activeProtocol());
    var error =
        assertThrows(DbmsException.class, () -> single.setProtocol(ProtocolType.STRICT_TIMESTAMP));
    assertEquals(ErrorCode.UNSUPPORTED_OPERATION, error.getCode());
  }

  @Test
  void otherComponentsSeeTheSameFacadeWhicheverProtocolIsActive() {
    dynamitedb.contracts.api.AccessController access = ccm;
    dynamitedb.contracts.api.TransactionManager transactions = ccm;
    ccm.setProtocol(ProtocolType.STRICT_TIMESTAMP);

    TransactionId tx = transactions.begin();
    assertEquals(AccessState.GRANTED, access.requestAccess(write(tx)).state());
    transactions.commit(tx);

    assertSame(TransactionStatus.COMMITTED, transactions.status(tx));
  }
}
