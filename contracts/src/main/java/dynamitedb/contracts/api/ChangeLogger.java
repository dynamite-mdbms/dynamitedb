package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.transaction.TransactionId;

// draf dari QP biar bisa compile, nanti ganti punya CC3
public interface ChangeLogger {

  LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write);
}
