package dynamitedb.frm;

import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class CrashRecoveryManager implements CrashRecovery {
  private final LogManager log;
  private final WalService wal;
  private final GenerationManifestStore manifests;
  private final RecoveryPageStore storage;
  private final UndoManager undo;
  private final StorageGenerationAdmin generations;
  private final MockRecoveryContext context;

  public CrashRecoveryManager(
      LogManager log,
      WalService wal,
      GenerationManifestStore manifests,
      RecoveryPageStore storage,
      UndoManager undo,
      StorageGenerationAdmin generations,
      MockRecoveryContext context) {
    this.log = Objects.requireNonNull(log, "log");
    this.wal = Objects.requireNonNull(wal, "wal");
    this.manifests = Objects.requireNonNull(manifests, "manifests");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.undo = Objects.requireNonNull(undo, "undo");
    this.generations = Objects.requireNonNull(generations, "generations");
    this.context = Objects.requireNonNull(context, "context");
  }

  public RecoveryResult recover() {
    context.invoked("CrashRecoveryManager.recover");
    return context.result();
  }

  public RecoveryState analyze(LogSequenceNumber startLsn) {
    Objects.requireNonNull(startLsn, "startLsn");
    context.invoked("CrashRecoveryManager.analyze");
    return context.state();
  }

  public void redo(RecoveryState state) {
    Objects.requireNonNull(state, "state");
    context.invoked("CrashRecoveryManager.redo");
  }

  public void undo(RecoveryState state) {
    Objects.requireNonNull(state, "state");
    context.invoked("CrashRecoveryManager.undo");
  }
}
