package dynamitedb.sm.draft;

import java.util.UUID;

/** Temporary FR4-owned port; stages remain isolated until the manifest is published by FRM. */
public interface HistoricalRecoveryStorage {
  SnapshotMetadata captureSnapshot(RecoveryBoundary boundary);

  RecoveryStage createStage(SnapshotMetadata snapshot, UUID id);

  void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn);

  void rebuildStageIndexes(RecoveryStage stage);

  SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary);
}
