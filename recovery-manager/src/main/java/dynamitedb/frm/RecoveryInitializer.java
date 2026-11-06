package dynamitedb.frm;

import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;

/** Buka database baru atau delegasi open existing ke crash recovery. */
public final class RecoveryInitializer {
  private final GenerationManifestStore manifest;
  private final CrashRecovery crash;
  private final RecoveryCoordinator coordinator;
  // context != null = mode mock M1; coordinator != null = mode real
  private final MockRecoveryContext context;

  public RecoveryInitializer(
      GenerationManifestStore manifest, CrashRecovery crash, MockRecoveryContext context) {
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.crash = Objects.requireNonNull(crash, "crash");
    this.coordinator = null;
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Jalur real: recovery gagal -> throw, sukses -> high-water dikirim ke CCM. */
  public RecoveryInitializer(
      GenerationManifestStore manifest, CrashRecovery crash, RecoveryCoordinator coordinator) {
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.crash = Objects.requireNonNull(crash, "crash");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.context = null;
  }

  public RecoveryResult createNew(SnapshotMetadata seed) {
    Objects.requireNonNull(seed, "seed");
    if (context != null) {
      context.invoked("RecoveryInitializer.createNew");
      return context.result();
    }
    throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Initialisasi database baru belum diwire");
  }

  public RecoveryResult openExisting() {
    if (context != null) context.invoked("RecoveryInitializer.openExisting");
    manifest.readActive();
    RecoveryResult result = crash.recover();
    if (context == null) {
      if (result.status() != RecoveryStatus.SUCCESS) {
        throw new DbmsException(ErrorCode.RECOVERY_FAILURE, result.message());
      }
      coordinator.initializeRecoveredState(result.highWater());
    }
    return result;
  }
}
