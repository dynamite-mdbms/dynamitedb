package dynamitedb.qp.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import dynamitedb.qp.FakeComponents;
import dynamitedb.qp.session.SessionContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionCoordinatorTest {

  private final FakeComponents fake = new FakeComponents();
  private final TransactionCoordinator coordinator = new TransactionCoordinator(fake, fake, fake);
  private final SessionContext session = new SessionContext(new SessionId(1), "client");

  @Test
  void beginUsesIdFromCcm() {
    TransactionId id = coordinator.begin(session);

    assertEquals(new TransactionId(10), id);
    assertEquals(Optional.of(id), session.activeTx());
  }

  @Test
  void nestedBeginIsRejected() {
    coordinator.begin(session);

    DbmsException error = assertThrows(DbmsException.class, () -> coordinator.begin(session));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
  }

  @Test
  void commitAndAbortWithoutTransactionAreRejected() {
    assertEquals(
        ErrorCode.INVALID_STATE,
        assertThrows(DbmsException.class, () -> coordinator.commit(session)).getCode());
    assertEquals(
        ErrorCode.INVALID_STATE,
        assertThrows(DbmsException.class, () -> coordinator.abort(session)).getCode());
  }

  @Test
  void commitClearsSessionAfterCcmCommit() {
    coordinator.begin(session);
    coordinator.commit(session);

    assertEquals(List.of("begin 10", "commit 10"), fake.events);
    assertTrue(session.activeTx().isEmpty());
  }

  @Test
  void failedCommitKeepsTransactionForAbort() {
    coordinator.begin(session);
    fake.commitFailure = new DbmsException(ErrorCode.WAL_FAILURE, "disk full");

    assertThrows(DbmsException.class, () -> coordinator.commit(session));
    assertTrue(session.activeTx().isPresent());

    coordinator.abort(session);
    assertTrue(session.activeTx().isEmpty());
  }

  @Test
  void abortSkipsTransactionAlreadyAbortedByCcm() {
    TransactionId id = coordinator.begin(session);
    fake.statuses.put(id, TransactionStatus.ABORTED);

    coordinator.abort(session);

    assertEquals(List.of("begin 10"), fake.events);
    assertTrue(session.activeTx().isEmpty());
  }

  @Test
  void authorizeWaitsUntilGranted() {
    TransactionId tx = new TransactionId(3);
    WaitToken ticket = new WaitToken(tx, UUID.randomUUID());
    fake.decisions.add(AccessDecision.waiting(ticket, "locked"));
    fake.decisions.add(AccessDecision.waiting(ticket, "still locked"));
    fake.decisions.add(AccessDecision.granted());

    coordinator.authorizeTable(tx, "t", AccessMode.READ);

    assertEquals(List.of("access READ", "await", "await"), fake.events);
  }

  @Test
  void abortedAccessThrowsAccessAborted() {
    fake.decisions.add(AccessDecision.aborted("deadlock victim"));

    DbmsException error =
        assertThrows(
            DbmsException.class,
            () -> coordinator.authorizeTable(new TransactionId(3), "t", AccessMode.WRITE));
    assertEquals(ErrorCode.ACCESS_ABORTED, error.getCode());
  }

  @Test
  void authorizeTableLocksWholeTable() {
    coordinator.authorizeTable(new TransactionId(3), "t", AccessMode.WRITE);

    assertEquals(ResourceId.table(7), fake.requests.getFirst().resource());
    assertEquals(AccessMode.WRITE, fake.requests.getFirst().mode());
  }
}
