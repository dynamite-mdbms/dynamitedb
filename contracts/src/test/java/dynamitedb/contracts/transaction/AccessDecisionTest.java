package dynamitedb.contracts.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessDecisionTest {
  @Test
  void rejectsNullReason() {
    assertThrows(
        NullPointerException.class,
        () -> new AccessDecision(AccessState.GRANTED, Optional.empty(), null));
  }

  @Test
  void enforcesTicketPresenceForEveryState() {
    WaitToken ticket = new WaitToken(new TransactionId(1L), UUID.randomUUID());

    assertEquals(
        AccessState.GRANTED,
        new AccessDecision(AccessState.GRANTED, Optional.empty(), "ok").state());
    assertEquals(
        AccessState.WAITING,
        new AccessDecision(AccessState.WAITING, Optional.of(ticket), "pending").state());
    assertEquals(
        AccessState.ABORTED,
        new AccessDecision(AccessState.ABORTED, Optional.empty(), "no").state());
    for (AccessState state : List.of(AccessState.GRANTED, AccessState.ABORTED)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AccessDecision(state, Optional.of(ticket), "invalid"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new AccessDecision(AccessState.WAITING, Optional.empty(), "invalid"));
  }

  @Test
  void rejectsNullRequiredComponents() {
    assertThrows(
        NullPointerException.class, () -> new AccessDecision(null, Optional.empty(), "reason"));
    assertThrows(
        NullPointerException.class, () -> new AccessDecision(AccessState.GRANTED, null, "reason"));
  }

  @Test
  void factoriesPreserveDecisionValues() {
    WaitToken ticket = new WaitToken(new TransactionId(2L), UUID.randomUUID());
    AccessDecision granted = AccessDecision.granted();
    AccessDecision waiting = AccessDecision.waiting(ticket, "pending reason");
    AccessDecision aborted = AccessDecision.aborted("abort reason");

    assertEquals(AccessState.GRANTED, granted.state());
    assertEquals(Optional.empty(), granted.ticket());
    assertEquals(AccessState.WAITING, waiting.state());
    assertSame(ticket, waiting.ticket().orElseThrow());
    assertEquals("pending reason", waiting.reason());
    assertEquals(AccessState.ABORTED, aborted.state());
    assertEquals(Optional.empty(), aborted.ticket());
    assertEquals("abort reason", aborted.reason());
    assertThrows(NullPointerException.class, () -> AccessDecision.waiting(null, "pending"));
    assertThrows(NullPointerException.class, () -> AccessDecision.waiting(ticket, null));
    assertThrows(NullPointerException.class, () -> AccessDecision.aborted(null));
  }
}
