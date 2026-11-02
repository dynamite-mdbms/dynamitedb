package dynamitedb.frm;

import dynamitedb.contracts.api.CheckpointService;
import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.HistoricalRecoveryService;
import dynamitedb.contracts.api.LogInspection;
import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.api.WalDurability;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.LogView;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.transaction.TransactionId;
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
