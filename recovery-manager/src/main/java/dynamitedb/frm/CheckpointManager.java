package dynamitedb.frm;

import dynamitedb.contracts.api.CheckpointService;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Milestone 1 mock only. Fixture responses and call recording without durable DBMS behavior. */
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

  @SuppressWarnings("try") // The guard is held for its lifetime, not called inside the block.
  public CheckpointResult checkpoint() {
    context.invoked("CheckpointManager.checkpoint");
    try (MaintenanceGuard ignored = coordinator.acquireMaintenance()) {
      LogSequenceNumber throughLsn = log.readHighWater().lsn();
      UUID checkpointId = UUID.randomUUID();
      LogSequenceNumber beginLsn =
          log.append(
              new CheckpointLogRecord(
                  log.nextCheckpointLsn(),
                  log.activeLineage(),
                  checkpointId,
                  CheckpointEvent.BEGIN,
                  throughLsn));
      log.flush(beginLsn);
      pages.flushDirtyThrough(throughLsn);
      LogSequenceNumber endLsn =
          log.append(
              new CheckpointLogRecord(
                  log.nextCheckpointLsn(),
                  log.activeLineage(),
                  checkpointId,
                  CheckpointEvent.END,
                  throughLsn));
      log.flush(endLsn);
      return new CheckpointResult(endLsn);
    }
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
