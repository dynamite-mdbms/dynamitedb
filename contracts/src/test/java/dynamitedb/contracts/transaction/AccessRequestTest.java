package dynamitedb.contracts.transaction;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessRequestTest {
  @Test
  void rejectsNullRequiredValuesAndPreservesRequestIdentity() {
    TransactionId tx = new TransactionId(3L);
    ResourceId resource = ResourceId.table(4L);
    AccessRequest request = new AccessRequest(tx, resource, AccessMode.WRITE);

    assertSame(tx, request.tx());
    assertSame(resource, request.resource());
    assertSame(AccessMode.WRITE, request.mode());
    assertThrows(
        NullPointerException.class, () -> new AccessRequest(null, resource, AccessMode.READ));
    assertThrows(NullPointerException.class, () -> new AccessRequest(tx, null, AccessMode.READ));
    assertThrows(NullPointerException.class, () -> new AccessRequest(tx, resource, null));

    UUID requestId = UUID.randomUUID();
    WaitToken ticket = new WaitToken(tx, requestId);
    assertSame(tx, ticket.tx());
    assertSame(requestId, ticket.requestId());
    assertThrows(NullPointerException.class, () -> new WaitToken(null, requestId));
    assertThrows(NullPointerException.class, () -> new WaitToken(tx, null));
  }
}
