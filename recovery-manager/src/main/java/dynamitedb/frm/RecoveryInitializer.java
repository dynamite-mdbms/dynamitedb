package dynamitedb.frm;

import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;
import java.util.Optional;

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
    RecoveryHighWater highWater =
        new RecoveryHighWater(seed.baseLsn(), new TransactionId(0), 0, Optional.empty());
    GenerationManifest initial =
        new GenerationManifest(seed.id(), seed.walLineage(), seed.baseLsn(), highWater);
    try {
      manifest.publishAtomically(initial);
      coordinator.initializeRecoveredState(highWater);
      return new RecoveryResult(RecoveryStatus.SUCCESS, highWater, "Database baru diinisialisasi");
    } catch (RuntimeException failure) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Gagal menginisialisasi database baru", failure);
    }
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
