package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.transaction.TransactionId;

public interface TransactionWal {
  void begin(TransactionId tx, long timestamp);

  LogSequenceNumber appendChange(ChangeRecord change);

  void commitDurably(TransactionId tx);
}
