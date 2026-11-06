package dynamitedb.frm;

import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;

/** Reverses a transaction through recovery storage port */
public final class UndoManager implements RollbackService {
  private final LogManager log;
  private final RecoveryPageStore storage;
  private final MockRecoveryContext context;

  public UndoManager(LogManager log, RecoveryPageStore storage) {
    this.log = Objects.requireNonNull(log, "log");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.context = null;
  }

  public UndoManager(LogManager log, RecoveryPageStore storage, MockRecoveryContext context) {
    this.log = Objects.requireNonNull(log, "log");
    this.storage = Objects.requireNonNull(storage, "storage");
    this.context = Objects.requireNonNull(context, "context");
  }

  public void rollbackCompletely(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    if (context != null) {
      context.invoked("UndoManager.rollbackCompletely");
      return;
    }
    synchronized (log) {
      var history = new ArrayList<LogRecord>();
      log.reverseByTx(tx).forEachRemaining(history::add);
      if (history.isEmpty())
        throw new DbmsException(ErrorCode.INVALID_STATE, "Unknown transaction: " + tx);
      LogRecord latest = history.getFirst();
      if (latest instanceof TransactionEventLogRecord event) {
        if (event.event == TxEvent.COMMIT)
          throw new DbmsException(ErrorCode.INVALID_STATE, "Transaction is committed: " + tx);
        if (event.event == TxEvent.ABORT_COMPLETE) {
          log.flush(event.lsn);
          return;
        }
      }
      var byLsn = new HashMap<LogSequenceNumber, LogRecord>();
      for (var record : history) byLsn.put(record.lsn, record);
      // Reapply CLRs in WAL order
      for (int i = history.size() - 1; i >= 0; i--) {
        if (history.get(i) instanceof CompensationLogRecord clr) {
          storage.applyRecovery(clr.mutation, clr.lsn);
        }
      }
      Optional<LogSequenceNumber> next = Optional.of(latest.lsn);
      while (next.isPresent()) {
        LogRecord record = byLsn.get(next.orElseThrow());
        if (record == null)
          throw new DbmsException(ErrorCode.CORRUPT_LOG, "Missing transaction log record");
        if (record instanceof CompensationLogRecord clr) {
          next = clr.undoNext;
        } else if (record instanceof ChangeLogRecord change) {
          CompensationLogRecord clr = emitClr(change);
          storage.applyRecovery(clr.mutation, clr.lsn);
          next = clr.undoNext;
        } else {
          next = record.prevLsn;
        }
      }
      log.finishAbort(tx);
    }
  }

  public void undoLosers(RecoveryState state) {
    Objects.requireNonNull(state, "state");
    if (context != null) {
      context.invoked("UndoManager.undoLosers");
      return;
    }
    for (var tx : state.losers()) rollbackCompletely(tx);
  }

  public CompensationLogRecord emitClr(ChangeLogRecord record) {
    Objects.requireNonNull(record, "record");
    if (context != null) {
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
    synchronized (log) {
      var lsn =
          log.appendCompensation(
              record.change.tx(),
              record.lsn,
              record.prevLsn,
              new RecoveryMutation(record.change.rid(), record.change.before()));
      var records = log.reverseByTx(record.change.tx());
      while (records.hasNext()) {
        var logged = records.next();
        if (logged.lsn.equals(lsn)) return (CompensationLogRecord) logged;
      }
      throw new DbmsException(ErrorCode.INVALID_STATE, "Appended compensation record is missing");
    }
  }
}
