package dynamitedb.frm;

import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Recovery startup: analysis, redo, undo. Constructor mock tetap ada buat test M1. */
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
    this(
        log,
        wal,
        manifests,
        storage,
        undo,
        generations,
        Objects.requireNonNull(context, "context"),
        true);
  }

  /** Jalur real; nggak ada call recording atau fixture response. */
  public CrashRecoveryManager(
      LogManager log,
      WalService wal,
      GenerationManifestStore manifests,
      RecoveryPageStore storage,
      UndoManager undo,
      StorageGenerationAdmin generations) {
    this(log, wal, manifests, storage, undo, generations, null, false);
  }

  private CrashRecoveryManager(
      LogManager log,
      WalService wal,
      GenerationManifestStore manifests,
      RecoveryPageStore storage,
      UndoManager undo,
      StorageGenerationAdmin generations,
      MockRecoveryContext context,
      boolean mock) {
    this.log = Objects.requireNonNull(log, "log");
    this.wal = Objects.requireNonNull(wal, "wal");
    this.manifests = Objects.requireNonNull(manifests, "manifests");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.undo = Objects.requireNonNull(undo, "undo");
    this.generations = Objects.requireNonNull(generations, "generations");
    this.context = mock ? Objects.requireNonNull(context, "context") : null;
  }

  @Override
  public RecoveryResult recover() {
    if (context != null) {
      context.invoked("CrashRecoveryManager.recover");
      return context.result();
    }
    try {
      GenerationManifest active = manifests.readActive();
      if (!active.walLineage().equals(log.activeLineage())) {
        throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "WAL aktif nggak cocok sama manifest");
      }
      if (!generations.readActiveManifest().equals(active)) {
        throw new DbmsException(
            ErrorCode.RECOVERY_FAILURE, "Generation storage nggak cocok sama manifest");
      }

      Analysis analysis = analyzeInternal(new LogSequenceNumber(0));
      redoFrom(analysis.redoStart());
      undo.undoLosers(analysis.state());
      storage.rebuildIndexes();

      RecoveryHighWater highWater = merge(active.highWater(), log.readHighWater());
      wal.ensureDurable(highWater.lsn());
      storage.flushDirtyThrough(highWater.lsn());
      if (!highWater.equals(active.highWater())) {
        manifests.publishAtomically(
            new GenerationManifest(
                active.activeId(), active.walLineage(), active.boundaryLsn(), highWater));
      }
      return new RecoveryResult(RecoveryStatus.SUCCESS, highWater, "Crash recovery selesai");
    } catch (RuntimeException failure) {
      return new RecoveryResult(
          RecoveryStatus.FAILED, safeHighWater(), "Crash recovery gagal: " + message(failure));
    }
  }

  /** startLsn cuma buat driver; recover() selalu analisis dari awal. */
  public RecoveryState analyze(LogSequenceNumber startLsn) {
    Objects.requireNonNull(startLsn, "startLsn");
    if (context != null) {
      context.invoked("CrashRecoveryManager.analyze");
      return context.state();
    }
    return analyzeInternal(startLsn).state();
  }

  /** Redo dari awal WAL; state cuma buat kontrak, idempoten lewat page LSN. */
  public void redo(RecoveryState state) {
    Objects.requireNonNull(state, "state");
    if (context != null) {
      context.invoked("CrashRecoveryManager.redo");
      return;
    }
    redoFrom(new LogSequenceNumber(0));
  }

  public void undo(RecoveryState state) {
    Objects.requireNonNull(state, "state");
    if (context != null) {
      context.invoked("CrashRecoveryManager.undo");
      return;
    }
    undo.undoLosers(state);
  }

  private Analysis analyzeInternal(LogSequenceNumber startLsn) {
    List<LogRecord> records = recordsFrom(startLsn);
    Set<TransactionId> begun = new HashSet<>();
    Set<TransactionId> committed = new HashSet<>();
    Set<TransactionId> aborted = new HashSet<>();
    LogSequenceNumber checkpointThrough = new LogSequenceNumber(0);

    for (LogRecord record : records) {
      if (record instanceof TransactionEventLogRecord event) {
        TransactionId tx = event.tx.orElseThrow();
        switch (event.event) {
          case BEGIN -> begun.add(tx);
          case COMMIT -> committed.add(tx);
          case ABORT_COMPLETE -> aborted.add(tx);
        }
      } else if (record instanceof CheckpointLogRecord checkpoint
          && checkpoint.event == CheckpointEvent.END
          && checkpoint.throughLsn.value() > checkpointThrough.value()) {
        checkpointThrough = checkpoint.throughLsn;
      }
    }

    Set<TransactionId> winners = Set.copyOf(committed);
    Set<TransactionId> losers = new HashSet<>(begun);
    losers.removeAll(committed);
    losers.removeAll(aborted);

    RecoveryHighWater highWater = log.readHighWater();
    RecoveryState state =
        new RecoveryState(winners, Set.copyOf(losers), highWater.lsn(), highWater);
    return new Analysis(state, checkpointThrough);
  }

  private void redoFrom(LogSequenceNumber startLsn) {
    for (LogRecord record : recordsFrom(startLsn)) {
      if (record instanceof ChangeLogRecord change) {
        storage.applyRecovery(
            new RecoveryMutation(change.change.rid(), change.change.after()), record.lsn);
      } else if (record instanceof CompensationLogRecord compensation) {
        storage.applyRecovery(compensation.mutation, record.lsn);
      }
    }
  }

  private List<LogRecord> recordsFrom(LogSequenceNumber startLsn) {
    var result = new ArrayList<LogRecord>();
    log.scanFrom(startLsn).forEachRemaining(result::add);
    return result;
  }

  private RecoveryHighWater safeHighWater() {
    try {
      return log.readHighWater();
    } catch (RuntimeException ignored) {
      return new RecoveryHighWater(
          new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());
    }
  }

  private static RecoveryHighWater merge(RecoveryHighWater left, RecoveryHighWater right) {
    return new RecoveryHighWater(
        new LogSequenceNumber(Math.max(left.lsn().value(), right.lsn().value())),
        new TransactionId(Math.max(left.maxTx().value(), right.maxTx().value())),
        Math.max(left.maxTimestamp(), right.maxTimestamp()),
        later(left.maxCommitTime(), right.maxCommitTime()));
  }

  private static Optional<Instant> later(Optional<Instant> left, Optional<Instant> right) {
    if (left.isEmpty()) return right;
    if (right.isEmpty()) return left;
    return left.orElseThrow().isAfter(right.orElseThrow()) ? left : right;
  }

  private static String message(RuntimeException failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }

  private record Analysis(RecoveryState state, LogSequenceNumber redoStart) {}
}
