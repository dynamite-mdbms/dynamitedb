package dynamitedb.ccm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.ResourceKind;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class Strict2PLProtocolTest {
  private final LockManager manager = new LockManager();
  private final Strict2PLProtocol protocol = new Strict2PLProtocol(manager);

  @Test
  void rejectsNullLockManager() {
    assertThrows(NullPointerException.class, () -> new Strict2PLProtocol(null));
  }

  @Test
  void retainsReadAndWritePermissionsUntilFinishAndDeliversWaitingTicket() {
    var resource = ResourceId.table(1);
    var reader = tx(1);
    var writer = tx(2);

    assertEquals(
        AccessState.GRANTED, protocol.request(request(reader, resource, AccessMode.READ)).state());
    var waitingWriter = protocol.request(request(writer, resource, AccessMode.WRITE));
    assertEquals(AccessState.WAITING, waitingWriter.state());
    assertEquals(
        AccessState.WAITING, protocol.request(request(tx(3), resource, AccessMode.READ)).state());

    protocol.onFinish(reader);

    assertEquals(
        AccessState.GRANTED, protocol.awaitAccess(waitingWriter.ticket().orElseThrow()).state());
    assertEquals(
        AccessState.WAITING, protocol.request(request(tx(3), resource, AccessMode.READ)).state());
    protocol.onFinish(writer);

    assertEquals(
        AccessState.GRANTED, protocol.request(request(tx(3), resource, AccessMode.READ)).state());
  }

  @Test
  void finishCancelsQueuedTicketWithoutReleasingAnotherOwnersLock() {
    var resource = ResourceId.table(1);
    var holder = tx(1);
    var queued = tx(2);
    assertEquals(
        AccessState.GRANTED, protocol.request(request(holder, resource, AccessMode.WRITE)).state());
    var waiting = protocol.request(request(queued, resource, AccessMode.READ));
    var token = waiting.ticket().orElseThrow();

    protocol.onFinish(queued);

    assertThrows(DbmsException.class, () -> protocol.awaitAccess(token));
    assertEquals(
        AccessState.WAITING, protocol.request(request(tx(3), resource, AccessMode.READ)).state());
    protocol.onFinish(holder);
    assertEquals(
        AccessState.GRANTED, protocol.request(request(tx(3), resource, AccessMode.READ)).state());
  }

  @Test
  void rejectsUnknownAndStaleTickets() {
    var unknown = new WaitToken(tx(1), UUID.randomUUID());
    assertInvalid(() -> protocol.awaitAccess(unknown));

    var resource = ResourceId.table(1);
    protocol.request(request(tx(1), resource, AccessMode.WRITE));
    var waiting = protocol.request(request(tx(2), resource, AccessMode.READ));
    var stale = waiting.ticket().orElseThrow();
    protocol.onFinish(tx(2));
    assertInvalid(() -> protocol.awaitAccess(stale));
  }

  @Test
  void rejectsRecordResources() {
    var resource = new ResourceId(ResourceKind.RECORD, 1, Optional.of(new RecordId(1, 1, 1, 1)));
    var exception =
        assertThrows(
            DbmsException.class, () -> protocol.request(request(tx(1), resource, AccessMode.READ)));
    assertEquals(ErrorCode.UNSUPPORTED_OPERATION, exception.getCode());
    assertTrue(manager.waitForGraph().edges().isEmpty());
  }

  private static void assertInvalid(Runnable action) {
    var exception = assertThrows(DbmsException.class, action::run);
    assertEquals(ErrorCode.INVALID_STATE, exception.getCode());
  }

  private static AccessRequest request(TransactionId tx, ResourceId resource, AccessMode mode) {
    return new AccessRequest(tx, resource, mode);
  }

  private static TransactionId tx(long id) {
    return new TransactionId(id);
  }
}
