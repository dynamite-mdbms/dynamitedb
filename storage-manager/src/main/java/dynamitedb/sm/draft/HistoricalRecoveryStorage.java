package dynamitedb.sm.draft;

import java.util.UUID;

/** Temporary FR4-owned port; stages remain isolated until the manifest is published by FRM. */
public interface HistoricalRecoveryStorage {
  SnapshotMetadata captureSnapshot(UUID walLineage, LogSequenceNumber baseLsn);

  RecoveryStage createStage(SnapshotMetadata snapshot);

  void replayStage(RecoveryStage stage, RecoveryMutation mutation, LogSequenceNumber lsn);

  void rebuildStageIndexes(RecoveryStage stage);

  SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary);
}
