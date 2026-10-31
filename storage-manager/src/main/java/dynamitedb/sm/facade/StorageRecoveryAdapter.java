package dynamitedb.sm.facade;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.draft.GenerationManifest;
import dynamitedb.sm.draft.HistoricalRecoveryStorage;
import dynamitedb.sm.draft.LogSequenceNumber;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.RecordImage;
import dynamitedb.sm.draft.RecoveryBoundary;
import dynamitedb.sm.draft.RecoveryMutation;
import dynamitedb.sm.draft.RecoveryPageStore;
import dynamitedb.sm.draft.RecoveryStage;
import dynamitedb.sm.draft.SealedGeneration;
import dynamitedb.sm.draft.SnapshotMetadata;
import dynamitedb.sm.draft.StorageGenerationAdmin;
import dynamitedb.sm.generation.GenerationStore;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.util.Objects;
import java.util.UUID;

/**
 * SM1 recovery adapter wiring for the diagram's page, historical-storage and generation ports. FRM
 * supplies boundary values rather than internal log records. Targets remain TODO stubs.
 */
public final class StorageRecoveryAdapter
    implements RecoveryPageStore, HistoricalRecoveryStorage, StorageGenerationAdmin {
  private final RecordStore records;
  private final GenerationStore generations;
  private final IndexManager indexes;
  private final BufferPool buffer;

  public StorageRecoveryAdapter(
      RecordStore records, GenerationStore generations, IndexManager indexes, BufferPool buffer) {
    this.records = Objects.requireNonNull(records, "records");
    this.generations = Objects.requireNonNull(generations, "generations");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
  }

  @Override
  public RecordImage readRecord(RecordId rid) {
    return records.read(rid);
  }

  @Override
  public void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
    records.applyRecovery(mutation, lsn);
  }

  @Override
  public void rebuildIndexes() {
    indexes.rebuildAll();
  }

  @Override
  public void flushDirtyThrough(LogSequenceNumber lsn) {
    buffer.flushDirtyThrough(lsn);
  }

  @Override
  public SnapshotMetadata captureSnapshot(RecoveryBoundary boundary) {
    return generations.captureSnapshot(boundary);
  }

  @Override
  public RecoveryStage createStage(SnapshotMetadata snapshot, UUID id) {
    return generations.createStage(snapshot, id);
  }

  @Override
  public void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn) {
    generations.replayInStage(stage, change, lsn);
  }

  @Override
  public void rebuildStageIndexes(RecoveryStage stage) {
    generations.rebuildStageIndexes(stage);
  }

  @Override
  public SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
    return generations.sealStage(stage, boundary);
  }

  @Override
  public GenerationManifest readActiveManifest() {
    return generations.readActiveManifest();
  }

  @Override
  public void switchToPublishedGeneration(GenerationManifest manifest) {
    generations.switchToPublishedGeneration(manifest);
  }
}
