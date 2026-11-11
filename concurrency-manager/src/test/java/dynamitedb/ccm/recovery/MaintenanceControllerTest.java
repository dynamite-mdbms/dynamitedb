package dynamitedb.ccm.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class MaintenanceControllerTest {
  @Test
  void failedRuntimeRecoveryRemainsClosedAfterLeaseCloseAndCanBeRetried() {
    var registry = new TransactionRegistry();
    var maintenance = new MaintenanceController(registry);
    maintenance.initializeRecoveredState(() -> {});
    var guard = maintenance.acquire();
    maintenance.beginRecovery();
    guard.close();

    assertThrows(DbmsException.class, registry::create);
    var retry = maintenance.acquire();
    maintenance.beginRecovery();
    maintenance.initializeRecoveredState(() -> {});
    assertTrue(registry.isAdmissionHeld());
    retry.close();
    assertFalse(registry.isAdmissionHeld());
  }

  @Test
  void startupStaysClosedUntilSuccessfulInitialization() {
    var registry = new TransactionRegistry();
    var maintenance = new MaintenanceController(registry);
    assertThrows(DbmsException.class, registry::create);
    assertThrows(DbmsException.class, maintenance::resumeAdmission);

    maintenance.initializeRecoveredState(() -> assertTrue(registry.isAdmissionHeld()));

    assertFalse(registry.isAdmissionHeld());
    assertEquals(1, registry.create().id().value());
  }

  @Test
  void failedInitializationKeepsAdmissionClosedUntilRetry() {
    var registry = new TransactionRegistry();
    var maintenance = new MaintenanceController(registry);

    assertThrows(
        IllegalStateException.class,
        () ->
            maintenance.initializeRecoveredState(
                () -> {
                  throw new IllegalStateException("recovery gagal");
                }));
    assertThrows(DbmsException.class, registry::create);

    maintenance.initializeRecoveredState(() -> {});
    assertFalse(registry.isAdmissionHeld());
  }

  @Test
  void acquireWaitsForFinalCleanupAndCloseResumesAdmissionOnlyOnce() throws Exception {
    var drained = new CountDownLatch(1);
    var registry =
        new TransactionRegistry() {
          @Override
          public boolean awaitNoActive(Duration timeout) throws InterruptedException {
            drained.countDown();
            return super.awaitNoActive(timeout);
          }
        };
    var maintenance = new MaintenanceController(registry);
    maintenance.initializeRecoveredState(() -> {});
    var tx = registry.create();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending = executor.submit(maintenance::acquire);
      assertTrue(drained.await(5, TimeUnit.SECONDS));
      assertThrows(DbmsException.class, registry::create);
      assertFalse(pending.isDone());

      tx.transition(TransactionStatus.FAILED);
      tx.transition(TransactionStatus.ABORTED);
      assertFalse(pending.isDone());
      registry.markFinished(tx);

      MaintenanceGuard guard = pending.get(5, TimeUnit.SECONDS);
      assertTrue(registry.isAdmissionHeld());
      guard.close();
      guard.close();
      assertFalse(registry.isAdmissionHeld());
    }
  }

  @Test
  void guardsAreExclusiveAndNestedAcquisitionIsRejected() throws Exception {
    var registry = new TransactionRegistry();
    var maintenance = new MaintenanceController(registry);
    maintenance.initializeRecoveredState(() -> {});
    MaintenanceGuard first = maintenance.acquire();
    assertThrows(DbmsException.class, maintenance::acquire);
    var attempting = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending =
          executor.submit(
              () -> {
                attempting.countDown();
                return maintenance.acquire();
              });
      assertTrue(attempting.await(5, TimeUnit.SECONDS));
      assertFalse(pending.isDone());
      first.close();
      MaintenanceGuard second = pending.get(5, TimeUnit.SECONDS);
      assertTrue(registry.isAdmissionHeld());
      first.close();
      assertTrue(registry.isAdmissionHeld());
      second.close();
      assertFalse(registry.isAdmissionHeld());
    } finally {
      first.close();
    }
  }

  @Test
  void interruptedDrainReleasesItsHoldAndPreservesInterrupt() throws Exception {
    var waiting = new CountDownLatch(1);
    var registry =
        new TransactionRegistry() {
          @Override
          public boolean awaitNoActive(Duration timeout) throws InterruptedException {
            waiting.countDown();
            return super.awaitNoActive(timeout);
          }
        };
    var maintenance = new MaintenanceController(registry);
    maintenance.initializeRecoveredState(() -> {});
    registry.create();
    var finished = new CountDownLatch(1);
    var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var worker =
        new Thread(
            () -> {
              try {
                var failure = assertThrows(DbmsException.class, maintenance::acquire);
                assertEquals(ErrorCode.RECOVERY_FAILURE, failure.getCode());
                assertTrue(Thread.currentThread().isInterrupted());
              } catch (Throwable failure) {
                observed.set(failure);
              } finally {
                finished.countDown();
              }
            });
    worker.start();
    try {
      assertTrue(waiting.await(5, TimeUnit.SECONDS));
      worker.interrupt();
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      assertEquals(null, observed.get());
      assertFalse(registry.isAdmissionHeld());
    } finally {
      worker.interrupt();
      worker.join(5000);
    }
  }

  @Test
  void explicitHoldsAndStartupHoldRemainIndependentOfTheLease() {
    var registry = new TransactionRegistry();
    var maintenance = new MaintenanceController(registry);
    maintenance.blockAdmission();
    MaintenanceGuard guard = maintenance.acquire();
    guard.close();
    maintenance.resumeAdmission();
    assertTrue(registry.isAdmissionHeld());
    maintenance.initializeRecoveredState(() -> {});
    assertFalse(registry.isAdmissionHeld());
  }
}
