package dynamitedb.contracts;

/** FRM consumer port implemented by SM for record-image recovery and index maintenance. */
public interface RecoveryPageStore {
  RecordImage readRecord(RecordId rid);

  void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn);

  void rebuildIndexes();

  void flushDirtyThrough(LogSequenceNumber lsn);
}
