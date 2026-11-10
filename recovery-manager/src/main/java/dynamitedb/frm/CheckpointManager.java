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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Coordinates checkpoint markers and page flushing through FRM and external ports. */
public final class CheckpointManager implements CheckpointService {
  private static final System.Logger LOGGER = System.getLogger(CheckpointManager.class.getName());
  private final RecoveryCoordinator coordinator;
  private final RecoveryPageStore pages;
  private final LogManager log;
  private final Object periodicLock = new Object();
  private ScheduledExecutorService periodic;

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
          log.appendCheckpoint(checkpointId, CheckpointEvent.BEGIN, throughLsn);
      log.flush(beginLsn);
      pages.flushDirtyThrough(throughLsn);
      LogSequenceNumber endLsn =
          log.appendCheckpoint(checkpointId, CheckpointEvent.END, throughLsn);
      log.flush(endLsn);
      return new CheckpointResult(endLsn);
    }
  }

  /** Starts fixed-delay checkpoints and rejects a second start until stopped. */
  public void startPeriodic(Duration interval) {
    Objects.requireNonNull(interval, "interval");
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("Interval must be positive");
    }
    long delay;
    try {
      delay = interval.toNanos();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Interval is too large", overflow);
    }
    synchronized (periodicLock) {
      if (periodic != null) {
        throw new IllegalStateException("Periodic checkpoint is already running");
      }
      periodic =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "frm-checkpoint");
                thread.setDaemon(true);
                return thread;
              });
      periodic.scheduleWithFixedDelay(
          () -> {
            try {
              checkpoint();
            } catch (RuntimeException failure) {
              LOGGER.log(System.Logger.Level.ERROR, "Periodic checkpoint failed", failure);
            }
          },
          delay,
          delay,
          TimeUnit.NANOSECONDS);
    }
  }

  /** Stops future ticks without interrupting a checkpoint already running. */
  public void stopPeriodic() {
    synchronized (periodicLock) {
      if (periodic != null) {
        periodic.shutdown();
        periodic = null;
      }
    }
  }
}
