package dynamitedb.frm;

import dynamitedb.contracts.api.CheckpointService;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Coordinates checkpoint markers and page flushing through FRM and external ports. */
public final class CheckpointManager implements CheckpointService {
  private final RecoveryCoordinator coordinator;
  private final RecoveryPageStore pages;
  private final LogManager log;

  public CheckpointManager(
      RecoveryCoordinator coordinator, RecoveryPageStore pages, LogManager log) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.pages = Objects.requireNonNull(pages, "pages");
    this.log = Objects.requireNonNull(log, "log");
  }

  @SuppressWarnings("try") // The guard is held for its lifetime, not called inside the block.
  public synchronized CheckpointResult checkpoint() {
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
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("Interval must be positive");
    }
  }

  public void stopPeriodic() {}
}
