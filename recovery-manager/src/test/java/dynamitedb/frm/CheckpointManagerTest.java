package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CheckpointService;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
  void failedMaintenanceAcquisitionDoesNotWriteMarkers() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.RECOVERY_FAILURE, "maintenance failed");
    environment.context().failOn("RecoveryCoordinator.acquireMaintenance", failure);

    assertSame(failure, assertThrows(DbmsException.class, environment.checkpoint()::checkpoint));
    assertEquals(List.of("RecoveryCoordinator.acquireMaintenance"), environment.context().calls());
    assertEquals(List.of(), environment.context().checkpointRecords());
  }

  @Test
  void failedBeginAppendDoesNotFlushPagesAndReleasesGuard() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.WAL_FAILURE, "begin append failed");
    environment.context().failOn("LogManager.append", failure);

    assertSame(failure, assertThrows(DbmsException.class, environment.checkpoint()::checkpoint));
    assertEquals(List.of(), environment.context().checkpointRecords());
    assertEquals(
        List.of(
            "RecoveryCoordinator.acquireMaintenance",
            "LogManager.readHighWater",
            "LogManager.append",
            "MaintenanceGuard.close"),
        environment.context().calls());
  }

  @Test
  void failedEndAppendDoesNotReportSuccessAndReleasesGuard() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.WAL_FAILURE, "end append failed");
    var manager =
        withPageHook(environment, () -> environment.context().failOn("LogManager.append", failure));

    assertSame(failure, assertThrows(DbmsException.class, manager::checkpoint));
    assertEquals(1, environment.context().checkpointRecords().size());
    assertEquals(
        1, environment.context().calls().stream().filter("LogManager.flush"::equals).count());
    assertEquals("MaintenanceGuard.close", environment.context().calls().getLast());
  }

  @Test
  void failedEndForceDoesNotReportSuccessAndReleasesGuard() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.WAL_FAILURE, "end force failed");
    var manager =
        withPageHook(environment, () -> environment.context().failOn("LogManager.flush", failure));

    assertSame(failure, assertThrows(DbmsException.class, manager::checkpoint));
    assertEquals(2, environment.context().checkpointRecords().size());
    assertEquals(
        2, environment.context().calls().stream().filter("LogManager.flush"::equals).count());
    assertEquals("MaintenanceGuard.close", environment.context().calls().getLast());
  }

  @Test
  void failedGuardCloseDoesNotReportSuccess() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.RECOVERY_FAILURE, "close failed");
    environment.context().failOn("MaintenanceGuard.close", failure);

    assertSame(failure, assertThrows(DbmsException.class, environment.checkpoint()::checkpoint));
    assertEquals(2, environment.context().checkpointRecords().size());
  }

  @Test
  void failedPageFlushRetainsPrimaryFailureWhenGuardCloseAlsoFails() {
    var environment = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.STORAGE_FAILURE, "page failed");
    var closeFailure = new DbmsException(ErrorCode.RECOVERY_FAILURE, "close failed");
    environment.context().failOn("RecoveryPageStore.flushDirtyThrough", failure);
    environment.context().failOn("MaintenanceGuard.close", closeFailure);

    var thrown = assertThrows(DbmsException.class, environment.checkpoint()::checkpoint);
    assertSame(failure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(closeFailure, thrown.getSuppressed()[0]);
    assertEquals(1, environment.context().checkpointRecords().size());
  }

  @Test
  void simultaneousCheckpointsDoNotInterleaveMarkerPairs() throws Exception {
    var environment = MockRecoveryEnvironment.create();
    var firstAtPages = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
    var secondAcquired = new CountDownLatch(1);
    var enteredPages = new AtomicInteger();
    var coordinator = environment.ports().coordinator();
    RecoveryCoordinator observingCoordinator =
        new RecoveryCoordinator() {
          private int acquisitions;

          @Override
          public synchronized MaintenanceGuard acquireMaintenance() {
            if (++acquisitions == 2) secondAcquired.countDown();
            return coordinator.acquireMaintenance();
          }

          @Override
          public long transactionTimestamp(TransactionId tx) {
            return coordinator.transactionTimestamp(tx);
          }

          @Override
          public void initializeRecoveredState(RecoveryHighWater highWater) {
            coordinator.initializeRecoveredState(highWater);
          }
        };
    var pages =
        pagesWithHook(
            environment,
            () -> {
              if (enteredPages.incrementAndGet() == 1) {
                firstAtPages.countDown();
                try {
                  if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("first checkpoint was never released");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                }
              }
            });
    var manager = new CheckpointManager(observingCoordinator, pages, environment.log());
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(manager::checkpoint);
      if (!firstAtPages.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("first checkpoint did not reach pages");
      }
      var second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                return manager.checkpoint();
              });
      if (!secondStarted.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("second checkpoint did not start");
      }
      assertFalse(second.isDone());
      assertFalse(secondAcquired.await(200, TimeUnit.MILLISECONDS));
      assertEquals(1, environment.context().checkpointRecords().size());
      releaseFirst.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
      var markers = environment.context().checkpointRecords();
      assertEquals(
          List.of(
              CheckpointEvent.BEGIN,
              CheckpointEvent.END,
              CheckpointEvent.BEGIN,
              CheckpointEvent.END),
          markers.stream().map(record -> record.event).toList());
      assertEquals(markers.get(0).checkpointId, markers.get(1).checkpointId);
      assertEquals(markers.get(2).checkpointId, markers.get(3).checkpointId);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new AssertionError("checkpoint workers did not stop");
      }
    }
  }

  private static CheckpointManager withPageHook(
      MockRecoveryEnvironment environment, Runnable hook) {
    return new CheckpointManager(
        environment.ports().coordinator(), pagesWithHook(environment, hook), environment.log());
  }

  private static RecoveryPageStore pagesWithHook(
      MockRecoveryEnvironment environment, Runnable hook) {
    var pages = environment.ports().pages();
    RecoveryPageStore withHook =
        new RecoveryPageStore() {
          @Override
          public RecordImage readRecord(RecordId rid) {
            return pages.readRecord(rid);
          }

          @Override
          public void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
            pages.applyRecovery(mutation, lsn);
          }

          @Override
          public void rebuildIndexes() {
            pages.rebuildIndexes();
          }

          @Override
          public void flushDirtyThrough(LogSequenceNumber lsn) {
            hook.run();
            pages.flushDirtyThrough(lsn);
          }
        };
    return withHook;
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

  @Test
  void periodicTickUsesManualCheckpointAndStopPreventsAnotherTick() throws Exception {
    var environment = MockRecoveryEnvironment.create();
    var firstTick = new CountDownLatch(1);
    var secondTick = new CountDownLatch(1);
    var ticks = new AtomicInteger();
    var manager =
        withPageHook(
            environment,
            () -> {
              if (ticks.incrementAndGet() == 1) firstTick.countDown();
              else secondTick.countDown();
            });
    try {
      manager.startPeriodic(Duration.ofMillis(100));
      assertTrue(firstTick.await(2, TimeUnit.SECONDS));
    } finally {
      manager.stopPeriodic();
    }
    assertFalse(secondTick.await(200, TimeUnit.MILLISECONDS));
    assertEquals(CheckpointEvent.BEGIN, environment.context().checkpointRecords().getFirst().event);
    assertTrue(environment.context().calls().contains("RecoveryCoordinator.acquireMaintenance"));
  }

  @Test
  void periodicStartRejectsInvalidOrRepeatedStartAndStopIsIdempotent() {
    var manager = MockRecoveryEnvironment.create().checkpoint();
    assertThrows(IllegalArgumentException.class, () -> manager.startPeriodic(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> manager.startPeriodic(Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class, () -> manager.startPeriodic(Duration.ofDays(200_000)));

    try {
      manager.startPeriodic(Duration.ofHours(1));
      assertThrows(IllegalStateException.class, () -> manager.startPeriodic(Duration.ofHours(1)));
    } finally {
      manager.stopPeriodic();
      manager.stopPeriodic();
    }
  }

  @Test
  void failedPeriodicTickDoesNotPreventTheNextTick() throws Exception {
    var environment = MockRecoveryEnvironment.create();
    var secondTick = new CountDownLatch(1);
    var ticks = new AtomicInteger();
    var manager =
        withPageHook(
            environment,
            () -> {
              if (ticks.incrementAndGet() == 1) {
                throw new DbmsException(ErrorCode.STORAGE_FAILURE, "first periodic flush failed");
              }
              secondTick.countDown();
            });
    try {
      manager.startPeriodic(Duration.ofMillis(30));
      assertTrue(secondTick.await(2, TimeUnit.SECONDS));
    } finally {
      manager.stopPeriodic();
    }
    assertEquals(2, ticks.get());
    assertEquals(CheckpointEvent.BEGIN, environment.context().checkpointRecords().get(0).event);
    assertEquals(CheckpointEvent.BEGIN, environment.context().checkpointRecords().get(1).event);
  }

  @Test
  void periodicTickWaitsForConcurrentManualCheckpoint() throws Exception {
    var environment = MockRecoveryEnvironment.create();
    var manualAtPages = new CountDownLatch(1);
    var releaseManual = new CountDownLatch(1);
    var periodicAtPages = new CountDownLatch(1);
    var ticks = new AtomicInteger();
    var manager =
        withPageHook(
            environment,
            () -> {
              if (ticks.incrementAndGet() == 1) {
                manualAtPages.countDown();
                try {
                  if (!releaseManual.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("manual checkpoint did not resume");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                }
              } else {
                periodicAtPages.countDown();
              }
            });
    var executor = Executors.newSingleThreadExecutor();
    try {
      var manual = executor.submit(manager::checkpoint);
      assertTrue(manualAtPages.await(5, TimeUnit.SECONDS));
      manager.startPeriodic(Duration.ofMillis(20));
      assertFalse(periodicAtPages.await(100, TimeUnit.MILLISECONDS));
      assertEquals(1, environment.context().checkpointRecords().size());
      releaseManual.countDown();
      manual.get(5, TimeUnit.SECONDS);
      assertTrue(periodicAtPages.await(2, TimeUnit.SECONDS));
    } finally {
      releaseManual.countDown();
      manager.stopPeriodic();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(
        List.of(CheckpointEvent.BEGIN, CheckpointEvent.END, CheckpointEvent.BEGIN),
        environment.context().checkpointRecords().stream()
            .limit(3)
            .map(record -> record.event)
            .toList());
  }
}
