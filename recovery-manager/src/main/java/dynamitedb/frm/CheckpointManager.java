package dynamitedb.frm;

import dynamitedb.contracts.CheckpointResult;
import dynamitedb.contracts.CheckpointService;
import dynamitedb.contracts.RecoveryCoordinator;
import dynamitedb.contracts.RecoveryPageStore;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.time.Duration;
import java.util.Objects;

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class CheckpointManager implements CheckpointService {
  private final RecoveryCoordinator coordinator;
  private final RecoveryPageStore pages;
  private final LogManager log;
  private final MockRecoveryContext context;

  public CheckpointManager(
      RecoveryCoordinator coordinator,
      RecoveryPageStore pages,
      LogManager log,
      MockRecoveryContext context) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.pages = Objects.requireNonNull(pages, "pages");
    this.log = Objects.requireNonNull(log, "log");
    this.context = Objects.requireNonNull(context, "context");
  }

  public CheckpointResult checkpoint() {
    context.invoked("CheckpointManager.checkpoint");
    return new CheckpointResult(context.lsn());
  }

  public void startPeriodic(Duration interval) {
    Objects.requireNonNull(interval, "interval");
    context.invoked("CheckpointManager.startPeriodic");
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("Mock interval must be positive");
    }
  }

  public void stopPeriodic() {
    context.invoked("CheckpointManager.stopPeriodic");
  }
}
