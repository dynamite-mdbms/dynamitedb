package dynamitedb.sm.generation;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.draft.GenerationManifest;
import dynamitedb.sm.draft.GenerationManifestStore;
import dynamitedb.sm.draft.LogSequenceNumber;
import dynamitedb.sm.draft.RecoveryBoundary;
import dynamitedb.sm.draft.RecoveryMutation;
import dynamitedb.sm.draft.RecoveryStage;
import dynamitedb.sm.draft.SealedGeneration;
import dynamitedb.sm.draft.SnapshotMetadata;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** SM1 / Jason Edward Salim: isolated snapshot/stage storage and runtime generation activation. */
public final class GenerationStore {
  private final Path storageRoot;
  private final GenerationManifestStore manifests;
  private final BufferPool buffer;
  private final RecordStore records;
  private final IndexManager indexes;

  public GenerationStore(
      Path storageRoot,
      GenerationManifestStore manifests,
      BufferPool buffer,
      RecordStore records,
      IndexManager indexes) {
    this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot");
    this.manifests = Objects.requireNonNull(manifests, "manifests");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.records = Objects.requireNonNull(records, "records");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
  }

  public SnapshotMetadata captureSnapshot(UUID walLineage, LogSequenceNumber baseLsn) {
    // TODO(SM1/SM2/SM4/FRM): Capture a consistent heap/catalog snapshot under maintenance.
    // The supplied lineage and base LSN must describe the same selected generation.
    throw new UnsupportedOperationException("TODO(SM1): capture a consistent storage snapshot");
  }

  public RecoveryStage createStage(SnapshotMetadata snapshot) {
    // TODO(SM1): Build an isolated workspace without changing the active heap, catalog or buffers.
    throw new UnsupportedOperationException("TODO(SM1): create a historical recovery stage");
  }

  public void replayStage(RecoveryStage stage, RecoveryMutation mutation, LogSequenceNumber lsn) {
    // TODO(SM1/SM3/FRM): Apply the supplied eligible mutation to the stage using canonical images.
    // FRM selects committed changes; staged writes must not reach active runtime handles.
    throw new UnsupportedOperationException("TODO(SM1): replay a staged recovery mutation");
  }

  public void rebuildStageIndexes(RecoveryStage stage) {
    // TODO(SM1/SM5): Build retained index definitions from the staged heap before sealing.
    throw new UnsupportedOperationException("TODO(SM1): rebuild staged indexes");
  }

  public SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
    // TODO(SM1/SM2): Validate and durably seal rebuilt data at the resolved replay boundary.
    // Sealing prepares a generation; only FRM's manifest store may publish it as active.
    throw new UnsupportedOperationException("TODO(SM1): seal a staged generation");
  }

  public GenerationManifest readActiveManifest() {
    // TODO(SM1/FR4): Read the single authoritative manifests store; do not keep a second manifest.
    throw new UnsupportedOperationException("TODO(SM1): read the active generation manifest");
  }

  public void refreshPublishedGeneration(GenerationManifest manifest) {
    // TODO(SM1/SM2/SM4/SM5/FRM): Refresh heap/catalog/index handles and invalidate old
    // cursors/buffers.
    // Verify the manifest has already been published; storage and WAL must select the same lineage.
    // This activation is not a second durable publication point.
    throw new UnsupportedOperationException("TODO(SM1): activate the published storage generation");
  }
}
