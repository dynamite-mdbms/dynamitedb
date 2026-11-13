package dynamitedb.ccm.timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimestampOrderingProtocolTest {
  private final TransactionRegistry registry = new TransactionRegistry();
  private final TimestampRegistry timestamps = new TimestampRegistry();
  private final StrictWriteIntentTable intents = new StrictWriteIntentTable();
  private final TimestampOrderingProtocol protocol =
      new TimestampOrderingProtocol(timestamps, intents, registry);

  @Test
  void rejectsNullDependencies() {
    assertThrows(
        NullPointerException.class, () -> new TimestampOrderingProtocol(null, intents, registry));
    assertThrows(
        NullPointerException.class,
        () -> new TimestampOrderingProtocol(timestamps, null, registry));
    assertThrows(
        NullPointerException.class, () -> new TimestampOrderingProtocol(timestamps, intents, null));
  }

  @Test
  void olderReadAfterNewerWriteIsAborted() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(newer, 5, AccessMode.WRITE)));
    protocol.onFinish(newer);
    assertAborted(protocol.request(request(older, 5, AccessMode.READ)));
  }

  @Test
  void olderWriteAfterNewerReadIsAborted() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(newer, 5, AccessMode.READ)));
    assertAborted(protocol.request(request(older, 5, AccessMode.WRITE)));
  }

  @Test
  void olderWriteAfterNewerWriteIsAborted() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(newer, 5, AccessMode.WRITE)));
    protocol.onFinish(newer);
    assertAborted(protocol.request(request(older, 5, AccessMode.WRITE)));
  }

  @Test
  void newerAccessAfterOlderWriteIsGrantedWhenInOrder() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(older, 5, AccessMode.WRITE)));
    protocol.onFinish(older); // older committed: its value may now be read or overwritten
    assertGranted(protocol.request(request(newer, 5, AccessMode.READ)));
    assertGranted(protocol.request(request(newer, 5, AccessMode.WRITE)));
  }

  @Test
  void transactionMayRewriteItsOwnRecord() {
    TransactionId tx = begin();
    assertGranted(protocol.request(request(tx, 5, AccessMode.WRITE)));
    assertGranted(protocol.request(request(tx, 5, AccessMode.WRITE)));
  }

  @Test
  void uncommittedWriteIntentMakesYoungerAccessWait() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(older, 5, AccessMode.WRITE)));
    var readDecision = protocol.request(request(newer, 5, AccessMode.READ));
    assertEquals(AccessState.WAITING, readDecision.state());
    assertTrue(readDecision.ticket().isPresent());
    var writeDecision = protocol.request(request(newer, 5, AccessMode.WRITE));
    assertEquals(AccessState.WAITING, writeDecision.state());
    assertTrue(writeDecision.ticket().isPresent());
    assertGranted(protocol.request(request(older, 5, AccessMode.READ)));
  }

  @Test
  void youngerAccessIsGrantedAfterOwnerCommit() throws Exception {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(older, 5, AccessMode.WRITE)));
    var ticket = protocol.request(request(newer, 5, AccessMode.READ)).ticket().orElseThrow();
    var awaiter = newWaiterThread(ticket);
    awaiter.start();
    var owner = registry.find(older).orElseThrow();
    owner.transition(TransactionStatus.PARTIALLY_COMMITTED);
    owner.transition(TransactionStatus.COMMITTED);
    protocol.onFinish(older);
    awaiter.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(awaiter.isAlive());
    assertEquals(AccessState.GRANTED, awaiter.decision.state());
  }

  @Test
  void youngerWaitingAccessIsAbortedWhenItsOwnTransactionFinishes() throws Exception {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(older, 5, AccessMode.WRITE)));
    var ticket = protocol.request(request(newer, 5, AccessMode.READ)).ticket().orElseThrow();
    var awaiter = newWaiterThread(ticket);
    awaiter.start();
    var waiter = registry.find(newer).orElseThrow();
    waiter.transition(TransactionStatus.FAILED);
    waiter.transition(TransactionStatus.ABORTED);
    protocol.onFinish(newer);
    awaiter.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(awaiter.isAlive());
    assertEquals(AccessState.ABORTED, awaiter.decision.state());
  }

  @Test
  void finishReleasesIntentSoOthersMayWrite() throws Exception {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(older, 5, AccessMode.WRITE)));
    var ticket = protocol.request(request(newer, 5, AccessMode.WRITE)).ticket().orElseThrow();
    var awaiter = newWaiterThread(ticket);
    awaiter.start();
    var owner = registry.find(older).orElseThrow();
    owner.transition(TransactionStatus.PARTIALLY_COMMITTED);
    owner.transition(TransactionStatus.COMMITTED);
    protocol.onFinish(older);
    awaiter.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(awaiter.isAlive());
    assertEquals(AccessState.GRANTED, awaiter.decision.state());
  }

  @Test
  void abortedAccessLeavesNoTrace() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(newer, 5, AccessMode.WRITE)));
    long writeTsBefore = timestamps.writeTs(ResourceId.table(5));
    long readTsBefore = timestamps.readTs(ResourceId.table(5));
    assertAborted(protocol.request(request(older, 5, AccessMode.WRITE)));
    assertAborted(protocol.request(request(older, 5, AccessMode.READ)));
    assertEquals(writeTsBefore, timestamps.writeTs(ResourceId.table(5)));
    assertEquals(readTsBefore, timestamps.readTs(ResourceId.table(5)));
  }

  @Test
  void waitingTicketIsRejected() {
    var error =
        assertThrows(
            DbmsException.class,
            () -> protocol.awaitAccess(new WaitToken(new TransactionId(1), UUID.randomUUID())));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
  }

  private TransactionId begin() {
    return registry.create().id();
  }

  private final class WaiterThread extends Thread {
    private final WaitToken ticket;
    private AccessDecision decision;

    private WaiterThread(WaitToken ticket) {
      this.ticket = ticket;
    }

    @Override
    public void run() {
      decision = protocol.awaitAccess(ticket);
    }
  }

  private WaiterThread newWaiterThread(WaitToken ticket) {
    return new WaiterThread(ticket);
  }

  private AccessRequest request(TransactionId tx, long table, AccessMode mode) {
    return new AccessRequest(tx, ResourceId.table(table), mode);
  }

  private void assertGranted(AccessDecision decision) {
    assertEquals(AccessState.GRANTED, decision.state(), "expected GRANTED: " + decision);
  }

  private void assertAborted(AccessDecision decision) {
    assertEquals(AccessState.ABORTED, decision.state(), "expected ABORTED: " + decision);
  }
}
