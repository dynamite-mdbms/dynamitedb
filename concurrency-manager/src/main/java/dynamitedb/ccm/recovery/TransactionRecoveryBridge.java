package dynamitedb.ccm.recovery;

import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.transaction.TransactionId;

public class TransactionRecoveryBridge {
  private TransactionWal wal;
  private RollbackService rollback;

  public void begin(TransactionId tx, long timestamp) {}

  public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
    return new LogSequenceNumber(0);
  }

  public void commit(TransactionId tx) {}

  public void rollback(TransactionId tx) {}
}
