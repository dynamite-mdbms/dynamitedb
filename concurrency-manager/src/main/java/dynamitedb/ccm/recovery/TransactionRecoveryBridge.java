package dynamitedb.ccm.recovery;

import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.transaction.TransactionId;

public class TransactionRecoveryBridge {
  private final TransactionWal wal;
  private final RollbackService rollback;

  public TransactionRecoveryBridge(TransactionWal wal, RollbackService rollback) {
    this.wal = wal;
    this.rollback = rollback;
  }

  public void begin(TransactionId tx, long timestamp) {
    wal.begin(tx, timestamp);
  }

  public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
    return new LogSequenceNumber(0);
  }

  public void commit(TransactionId tx) {
    wal.commitDurably(tx);
  }

  public void rollback(TransactionId tx) {
    rollback.rollbackCompletely(tx);
  }
}
