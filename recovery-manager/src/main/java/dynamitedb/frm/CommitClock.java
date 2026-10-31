package dynamitedb.frm;

import dynamitedb.frm.mock.MockRecoveryContext;
import java.time.Instant;
import java.util.Objects;

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class CommitClock {
  private final MockRecoveryContext context;

  public CommitClock(MockRecoveryContext context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  public Instant assign(Instant wallUtc) {
    Objects.requireNonNull(wallUtc, "wallUtc");
    context.invoked("CommitClock.assign");
    return context.commitTime();
  }

  public void seed(Instant lastCommitTime) {
    Objects.requireNonNull(lastCommitTime, "lastCommitTime");
    context.invoked("CommitClock.seed");
  }
}
