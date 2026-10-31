package dynamitedb.contracts.transaction;

import java.util.Objects;
import java.util.Optional;

public record AccessDecision(AccessState state, Optional<WaitToken> ticket, String reason) {
  public AccessDecision {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(ticket, "ticket");
    if ((state == AccessState.WAITING) != ticket.isPresent()) {
      throw new IllegalArgumentException("Ticket wajib ada tepat ketika state WAITING");
    }
  }

  public static AccessDecision granted() {
    return new AccessDecision(AccessState.GRANTED, Optional.empty(), "granted");
  }

  public static AccessDecision waiting(WaitToken ticket, String reason) {
    return new AccessDecision(AccessState.WAITING, Optional.of(ticket), reason);
  }

  public static AccessDecision aborted(String reason) {
    return new AccessDecision(AccessState.ABORTED, Optional.empty(), reason);
  }
}
