package dynamitedb.ccm.timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.UUID;
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
  void uncommittedWriteIntentBlocksAllOtherAccess() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(older, 5, AccessMode.WRITE)));
    assertAborted(protocol.request(request(newer, 5, AccessMode.READ)));
    assertAborted(protocol.request(request(newer, 5, AccessMode.WRITE)));
    assertGranted(protocol.request(request(older, 5, AccessMode.READ)));
  }

  @Test
  void finishReleasesIntentSoOthersMayWrite() {
    TransactionId older = begin();
    TransactionId newer = begin();
    assertGranted(protocol.request(request(older, 5, AccessMode.WRITE)));
    assertAborted(protocol.request(request(newer, 5, AccessMode.WRITE)));
    protocol.onFinish(older);
    assertGranted(protocol.request(request(newer, 5, AccessMode.WRITE)));
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
