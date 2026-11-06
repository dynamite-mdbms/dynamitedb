package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryInitializerRealTest {
  @TempDir Path directory;

  @Test
  void failedRecoveryThrowsBeforeCcmInitialization() {
    var coordinator = new RecordingCoordinator();
    var initializer = new RecoveryInitializer(manifests(), failedRecovery(), coordinator);

    assertThrows(DbmsException.class, initializer::openExisting);
    assertFalse(coordinator.initialized.get());
  }

  @Test
  void successfulRecoveryPublishesHighWaterToCcm() {
    var highWater =
        new RecoveryHighWater(new LogSequenceNumber(7), new TransactionId(3), 3, Optional.empty());
    var coordinator = new RecordingCoordinator();
    var initializer =
        new RecoveryInitializer(
            manifests(),
            () -> new RecoveryResult(RecoveryStatus.SUCCESS, highWater, "ok"),
            coordinator);

    var result = initializer.openExisting();

    assertEquals(RecoveryStatus.SUCCESS, result.status());
    assertTrue(coordinator.initialized.get());
    assertEquals(highWater, coordinator.highWater.get());
  }

  private GenerationManifestStore manifests() {
    var highWater =
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());
    var initial =
        new GenerationManifest(new UUID(0, 1), new UUID(0, 2), new LogSequenceNumber(0), highWater);
    return new AtomicGenerationManifestStore(directory, initial, new MockRecoveryContext());
  }

  private static CrashRecovery failedRecovery() {
    var highWater =
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());
    return () -> new RecoveryResult(RecoveryStatus.FAILED, highWater, "boom");
  }

  private static final class RecordingCoordinator implements RecoveryCoordinator {
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicReference<RecoveryHighWater> highWater = new AtomicReference<>();

    @Override
    public MaintenanceGuard acquireMaintenance() {
      return () -> {};
    }

    @Override
    public long transactionTimestamp(TransactionId tx) {
      return 0;
    }

    @Override
    public void initializeRecoveredState(RecoveryHighWater highWater) {
      this.highWater.set(highWater);
      initialized.set(true);
    }
  }
}
