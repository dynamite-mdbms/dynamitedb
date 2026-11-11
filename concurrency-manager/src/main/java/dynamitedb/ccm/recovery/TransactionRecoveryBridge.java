package dynamitedb.ccm.recovery;

import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.api.TransactionWal;
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
    wal.begin(Objects.requireNonNull(tx, "tx"), timestamp);
  }

  public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(write, "write");
    if (!tx.equals(write.tx())) {
      throw new IllegalArgumentException("Prepared write transaction does not match caller");
    }
    return wal.appendChange(
        new ChangeRecord(
            tx, write.table(), write.rid(), write.before(), write.after()));
  }

  public void commit(TransactionId tx) {
    wal.commitDurably(Objects.requireNonNull(tx, "tx"));
  }

  public void rollback(TransactionId tx) {
    rollback.rollbackCompletely(Objects.requireNonNull(tx, "tx"));
  }
}
