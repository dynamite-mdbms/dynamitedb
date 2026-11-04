package dynamitedb.frm;

import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;
import java.util.Optional;

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class UndoManager implements RollbackService {
  private final LogManager log;
  private final RecoveryPageStore storage;
  private final MockRecoveryContext context;

  public UndoManager(LogManager log, RecoveryPageStore storage, MockRecoveryContext context) {
    this.log = Objects.requireNonNull(log, "log");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.context = Objects.requireNonNull(context, "context");
  }

  public void rollbackCompletely(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    context.invoked("UndoManager.rollbackCompletely");
  }

  public void undoLosers(RecoveryState state) {
    Objects.requireNonNull(state, "state");
    context.invoked("UndoManager.undoLosers");
  }

  public CompensationLogRecord emitClr(ChangeLogRecord record) {
    Objects.requireNonNull(record, "record");
    context.invoked("UndoManager.emitClr");
    return new CompensationLogRecord(
        context.lsn(),
        record.change.tx(),
        Optional.empty(),
        record.walLineage,
        record.lsn,
        record.prevLsn,
        new RecoveryMutation(record.change.rid(), record.change.before()));
  }
}
