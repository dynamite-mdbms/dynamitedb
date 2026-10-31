package dynamitedb.sm.facade;

import dynamitedb.contracts.api.HistoricalRecoveryStorage;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.generation.GenerationStore;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.util.Objects;
import java.util.UUID;

/** Implementasi port recovery FRM di atas komponen internal SM. */
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
