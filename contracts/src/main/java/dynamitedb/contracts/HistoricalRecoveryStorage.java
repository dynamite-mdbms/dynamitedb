package dynamitedb.contracts;

import java.util.UUID;

public interface HistoricalRecoveryStorage {
  SnapshotMetadata captureSnapshot(RecoveryBoundary boundary);

  RecoveryStage createStage(SnapshotMetadata snapshot, UUID id);

  void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn);

  void rebuildStageIndexes(RecoveryStage stage);

  SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary);
}
