package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;

/** Operasi pemulihan halaman yang disediakan SM untuk FRM (FR-04, FR-07). */
public interface RecoveryPageStore {
  RecordImage readRecord(RecordId rid);

  void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn);

  void rebuildIndexes();

  void flushDirtyThrough(LogSequenceNumber lsn);
}
