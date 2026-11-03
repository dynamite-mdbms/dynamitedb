package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.api.CheckpointService;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.frm.mock.MockRecoveryEnvironment;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckpointManagerTest {
  @Test
  void checkpointForcesWalBeforePagesAndEndBeforeReleasingMaintenance() {
    var environment = MockRecoveryEnvironment.create();
    CheckpointService service = environment.recovery();

    CheckpointResult result = service.checkpoint();

    assertEquals(new LogSequenceNumber(3), result.checkpointLsn());
    var markers = environment.context().checkpointRecords();
    assertEquals(2, markers.size());
    assertEquals(CheckpointEvent.BEGIN, markers.get(0).event);
    assertEquals(CheckpointEvent.END, markers.get(1).event);
    assertEquals(markers.get(0).checkpointId, markers.get(1).checkpointId);
    assertEquals(environment.context().lineage(), markers.get(0).walLineage);
    assertEquals(environment.context().lineage(), markers.get(1).walLineage);
    assertEquals(new LogSequenceNumber(1), markers.get(0).throughLsn);
    assertEquals(markers.get(0).throughLsn, markers.get(1).throughLsn);
    assertEquals(result.checkpointLsn(), markers.get(1).lsn);
    assertEquals(
        List.of(
            "RecoveryCoordinator.acquireMaintenance",
            "LogManager.readHighWater",
            "LogManager.append",
            "LogManager.flush",
            "RecoveryPageStore.flushDirtyThrough",
            "LogManager.append",
            "LogManager.flush",
            "MaintenanceGuard.close"),
        environment.context().calls());
  }

  @Test
  void failedPageFlushDoesNotWriteEndAndReleasesMaintenance() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.STORAGE_FAILURE, "mock flush failed");
    environment.context().failOn("RecoveryPageStore.flushDirtyThrough", failure);

    assertSame(failure, assertThrows(DbmsException.class, environment.checkpoint()::checkpoint));
    assertEquals(1, environment.context().checkpointRecords().size());
    assertEquals(CheckpointEvent.BEGIN, environment.context().checkpointRecords().get(0).event);
    assertEquals(
        List.of(
            "RecoveryCoordinator.acquireMaintenance",
            "LogManager.readHighWater",
            "LogManager.append",
            "LogManager.flush",
            "RecoveryPageStore.flushDirtyThrough",
            "MaintenanceGuard.close"),
        environment.context().calls());
  }

  @Test
  void failedWalForceDoesNotFlushPagesAndReleasesMaintenance() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.WAL_FAILURE, "mock WAL force failed");
    environment.context().failOn("LogManager.flush", failure);

    assertSame(failure, assertThrows(DbmsException.class, environment.checkpoint()::checkpoint));
    assertEquals(1, environment.context().checkpointRecords().size());
    assertEquals(
        List.of(
            "RecoveryCoordinator.acquireMaintenance",
            "LogManager.readHighWater",
            "LogManager.append",
            "LogManager.flush",
            "MaintenanceGuard.close"),
        environment.context().calls());
  }

  @Test
  void checkpointDoesNotDependOnMockCheckpointHook() {
    var environment = MockRecoveryEnvironment.create();
    environment
        .context()
        .failOn(
            "CheckpointManager.checkpoint",
            new DbmsException(ErrorCode.RECOVERY_FAILURE, "mock-only hook failed"));

    assertEquals(new LogSequenceNumber(3), environment.recovery().checkpoint().checkpointLsn());
  }

  @Test
  void periodicMethodsAreCallable() {
    CheckpointManager manager = MockRecoveryEnvironment.create().checkpoint();

    assertDoesNotThrow(
        () -> {
          manager.startPeriodic(Duration.ofSeconds(1));
          manager.stopPeriodic();
        });
  }
}
