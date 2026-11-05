package dynamitedb.frm;

import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;

/** Mock initialization; existing-state opening delegates to the injected crash recovery port. */
public final class RecoveryInitializer {
  private final GenerationManifestStore manifest;
  private final CrashRecovery crash;
  private final MockRecoveryContext context;

  public RecoveryInitializer(
      GenerationManifestStore manifest, CrashRecovery crash, MockRecoveryContext context) {
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.crash = Objects.requireNonNull(crash, "crash");
    this.context = Objects.requireNonNull(context, "context");
  }

  public RecoveryResult createNew(SnapshotMetadata seed) {
    Objects.requireNonNull(seed, "seed");
    context.invoked("RecoveryInitializer.createNew");
    return context.result();
  }

  public RecoveryResult openExisting() {
    context.invoked("RecoveryInitializer.openExisting");
    manifest.readActive();
    return crash.recover();
  }
}
