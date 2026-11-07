package dynamitedb.ccm.recovery;

import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Objects;

public class TransactionRecoveryBridge {
  private final TransactionWal wal;
  private final RollbackService rollback;

  public TransactionRecoveryBridge(TransactionWal wal, RollbackService rollback) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.rollback = Objects.requireNonNull(rollback, "rollback");
  }

  public void begin(TransactionId tx, long timestamp) {
    wal.begin(tx, timestamp);
  }

  public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(write, "write");
    if (!tx.equals(write.tx())) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Prepared write milik transaksi lain");
    }
    ChangeRecord change =
        new ChangeRecord(tx, write.table(), write.rid(), write.before(), write.after());
    try {
      LogSequenceNumber lsn = wal.appendChange(change);
      if (lsn == null || lsn.value() <= 0) {
        throw new DbmsException(ErrorCode.WAL_FAILURE, "FRM tidak mengembalikan LSN yang valid");
      }
      return lsn;
    } catch (RuntimeException failure) {
      throw new DbmsException(
          ErrorCode.WAL_FAILURE, "Pencatatan perubahan transaksi " + tx + " gagal", failure);
    }
  }

  public void commit(TransactionId tx) {
    wal.commitDurably(tx);
  }

  public void rollback(TransactionId tx) {
    rollback.rollbackCompletely(tx);
  }
}
