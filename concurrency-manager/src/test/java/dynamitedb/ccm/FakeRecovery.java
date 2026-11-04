package dynamitedb.ccm;

import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;

final class FakeRecovery implements TransactionWal, RollbackService {
  private final List<String> events;
  volatile boolean failBegin;
  volatile boolean failCommit;
  volatile boolean failRollback;

  FakeRecovery(List<String> events) {
    this.events = events;
  }

  @Override
  public void begin(TransactionId tx, long timestamp) {
    if (failBegin) {
      throw new IllegalStateException("log penuh");
    }
    events.add("wal begin " + tx + " ts " + timestamp);
  }

  @Override
  public LogSequenceNumber appendChange(ChangeRecord change) {
    events.add("wal change " + change.tx());
    return new LogSequenceNumber(1);
  }

  @Override
  public void commitDurably(TransactionId tx) {
    if (failCommit) {
      throw new IllegalStateException("disk penuh");
    }
    events.add("wal commit " + tx);
  }

  @Override
  public void rollbackCompletely(TransactionId tx) {
    if (failRollback) {
      throw new IllegalStateException("undo gagal");
    }
    events.add("rollback " + tx);
  }
}
