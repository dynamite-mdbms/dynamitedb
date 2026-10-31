package dynamitedb.sm.draft;

/** Temporary FR5-owned recovery port provided by SM1's StorageRecoveryAdapter. */
public interface RecoveryPageStore {
  RecordImage readImage(RecordId rid);

  void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn);

  void rebuildIndexes();

  void flushThrough(LogSequenceNumber lsn);
}
