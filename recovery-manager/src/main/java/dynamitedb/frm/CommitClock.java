package dynamitedb.frm;

import dynamitedb.frm.mock.MockRecoveryContext;
import java.time.Instant;
import java.util.Objects;

/** Assigns strictly increasing UTC commit times, including after a clock rollback. */
public final class CommitClock {
  private final MockRecoveryContext context;
  private Instant last;

  public CommitClock() {
    this.context = null;
  }

  public CommitClock(MockRecoveryContext context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  public Instant assign(Instant wallUtc) {
    Objects.requireNonNull(wallUtc, "wallUtc");
    if (context != null) {
      context.invoked("CommitClock.assign");
      return context.commitTime();
    }
    synchronized (this) {
      if (last != null && !wallUtc.isAfter(last)) {
        try {
          wallUtc = last.plusNanos(1);
        } catch (RuntimeException overflow) {
          throw new IllegalStateException("Commit clock overflow", overflow);
        }
      }
      last = wallUtc;
      return wallUtc;
    }
  }

  public void seed(Instant lastCommitTime) {
    Objects.requireNonNull(lastCommitTime, "lastCommitTime");
    if (context != null) {
      context.invoked("CommitClock.seed");
      return;
    }
    synchronized (this) {
      if (last == null || lastCommitTime.isAfter(last)) last = lastCommitTime;
    }
  }
}
