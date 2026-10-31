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
 * SM1 recovery adapter wiring; delegates to the same storage services used by the normal facade.
 * FRM supplies boundary values rather than its internal log records. All targets remain TODO stubs.
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
  public RecordImage readImage(RecordId rid) {
    return records.readImage(rid);
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
  public void flushThrough(LogSequenceNumber lsn) {
    buffer.flushThrough(lsn);
  }

  @Override
  public SnapshotMetadata captureSnapshot(UUID walLineage, LogSequenceNumber baseLsn) {
    return generations.captureSnapshot(walLineage, baseLsn);
  }

  @Override
  public RecoveryStage createStage(SnapshotMetadata snapshot) {
    return generations.createStage(snapshot);
  }

  @Override
  public void replayStage(RecoveryStage stage, RecoveryMutation mutation, LogSequenceNumber lsn) {
    generations.replayStage(stage, mutation, lsn);
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
  public void refreshPublishedGeneration(GenerationManifest manifest) {
    generations.refreshPublishedGeneration(manifest);
  }
}
