package dynamitedb.sm.draft;

/** Temporary SM1 mutation port: prepare, log through CCM/FRM, then apply the matching write. */
public interface WriteStore {
  PreparedWrite prepareInsert(TransactionId tx, String table, Row row);

  PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row);

  PreparedWrite prepareDelete(TransactionId tx, RecordId rid);

  void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn);

  void cancelPrepared(PreparedWrite prepared);
}
