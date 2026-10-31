package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import java.util.UUID;

/** Staging recovery historis yang disediakan SM untuk FRM (FR-05). */
public interface HistoricalRecoveryStorage {
  SnapshotMetadata captureSnapshot(RecoveryBoundary boundary);

  RecoveryStage createStage(SnapshotMetadata snapshot, UUID id);

  void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn);

  void rebuildStageIndexes(RecoveryStage stage);

  SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary);
}
