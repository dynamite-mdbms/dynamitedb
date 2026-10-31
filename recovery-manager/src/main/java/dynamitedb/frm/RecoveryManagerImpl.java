package dynamitedb.frm;

import dynamitedb.contracts.ChangeRecord;
import dynamitedb.contracts.CheckpointResult;
import dynamitedb.contracts.CheckpointService;
import dynamitedb.contracts.CrashRecovery;
import dynamitedb.contracts.HistoricalRecoveryService;
import dynamitedb.contracts.LogFilter;
import dynamitedb.contracts.LogInspection;
import dynamitedb.contracts.LogSequenceNumber;
import dynamitedb.contracts.LogView;
import dynamitedb.contracts.RecoveryResult;
import dynamitedb.contracts.RecoveryTarget;
import dynamitedb.contracts.RollbackService;
import dynamitedb.contracts.TransactionId;
import dynamitedb.contracts.TransactionWal;
import dynamitedb.contracts.WalDurability;
import java.util.Objects;

/** Composite Milestone 1 facade; all operations share the injected mock services. */
public final class RecoveryManagerImpl
    implements TransactionWal,
        RollbackService,
        WalDurability,
        CrashRecovery,
        CheckpointService,
        HistoricalRecoveryService,
        LogInspection {
  private final WalService wal;
  private final UndoManager rollback;
  private final CrashRecoveryManager crash;
  private final HistoricalRecoveryManager historical;
  private final CheckpointManager checkpoint;

  public RecoveryManagerImpl(
      WalService wal,
      UndoManager rollback,
      CrashRecoveryManager crash,
      HistoricalRecoveryManager historical,
      CheckpointManager checkpoint) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.rollback = Objects.requireNonNull(rollback, "rollback");
    this.crash = Objects.requireNonNull(crash, "crash");
    this.historical = Objects.requireNonNull(historical, "historical");
    this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
  }

  @Override
  public void begin(TransactionId tx, long timestamp) {
    wal.begin(tx, timestamp);
  }

  @Override
  public LogSequenceNumber appendChange(ChangeRecord change) {
    return wal.appendChange(change);
  }

  @Override
  public void commitDurably(TransactionId tx) {
    wal.commitDurably(tx);
  }

  @Override
  public void rollbackCompletely(TransactionId tx) {
    rollback.rollbackCompletely(tx);
  }

  @Override
  public void ensureDurable(LogSequenceNumber lsn) {
    wal.ensureDurable(lsn);
  }

  @Override
  public RecoveryResult recover() {
    return crash.recover();
  }

  @Override
  public CheckpointResult checkpoint() {
    return checkpoint.checkpoint();
  }

  @Override
  public RecoveryResult recoverTo(RecoveryTarget target) {
    return historical.recoverTo(target);
  }

  @Override
  public LogView readLog(LogFilter filter) {
    return wal.readLog(filter);
  }
}
