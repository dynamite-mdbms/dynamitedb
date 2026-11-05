package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.transaction.TransactionId;

public interface ChangeLogger {
  LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write);
}
