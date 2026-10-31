package dynamitedb.contracts.storage;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.transaction.TransactionId;

// draf dari QP biar bisa compile, nanti ganti punya SM1
public interface WriteStore {

  PreparedWrite prepareInsert(TransactionId tx, String table, Row row);

  PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row);

  PreparedWrite prepareDelete(TransactionId tx, RecordId rid);

  void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn);

  void cancelPrepared(PreparedWrite prepared);
}
