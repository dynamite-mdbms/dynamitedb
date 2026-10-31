package dynamitedb.sm.generation;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.catalog.CatalogManager;
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

/** SM1 / Jason Edward Salim: isolated snapshots/stages and runtime generation activation. */
public final class GenerationStore {
  private final Path root;
  private final GenerationManifestStore manifests;
  private final BufferPool buffer;
  private final CatalogManager catalog;
  private final IndexManager indexes;
  private final RecordStore records;

  public GenerationStore(
      Path root,
      GenerationManifestStore manifests,
      BufferPool buffer,
      CatalogManager catalog,
      IndexManager indexes,
      RecordStore records) {
    this.root = Objects.requireNonNull(root, "root");
    this.manifests = Objects.requireNonNull(manifests, "manifests");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
    this.records = Objects.requireNonNull(records, "records");
  }

  public SnapshotMetadata captureSnapshot(RecoveryBoundary boundary) {
    // TODO(SM1/SM2/SM4/FRM): Capture a consistent heap/catalog snapshot under maintenance.
    // Resolve the source WAL lineage from the authoritative manifest at this recovery boundary.
    throw new UnsupportedOperationException("TODO(SM1): capture a consistent storage snapshot");
  }

  public RecoveryStage createStage(SnapshotMetadata snapshot, UUID id) {
    // TODO(SM1): Build an isolated workspace with the supplied identity from the selected snapshot.
    // Do not change the active heap, catalog or buffers.
    throw new UnsupportedOperationException("TODO(SM1): create a historical recovery stage");
  }

  public void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn) {
    // TODO(SM1/SM3/FRM): Apply this eligible change only to the stage using canonical row images.
    // FRM selects committed changes; staged writes must not reach active runtime handles.
    throw new UnsupportedOperationException("TODO(SM1): replay a staged recovery change");
  }

  public void rebuildStageIndexes(RecoveryStage stage) {
    // TODO(SM1/SM5): Rebuild retained definitions from the staged heap before sealing.
    throw new UnsupportedOperationException("TODO(SM1): rebuild staged indexes");
  }

  public SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
    // TODO(SM1/SM2): Validate and durably seal rebuilt data at the resolved replay boundary.
    // Only FRM's manifest store may publish the prepared generation as active.
    throw new UnsupportedOperationException("TODO(SM1): seal a staged generation");
  }

  public GenerationManifest readActiveManifest() {
    // TODO(SM1/FR4): Read the authoritative manifests store; do not keep a second manifest.
    throw new UnsupportedOperationException("TODO(SM1): read the active generation manifest");
  }

  public void switchToPublishedGeneration(GenerationManifest manifest) {
    // TODO(SM1/SM2/SM4/SM5/FRM): Refresh shared heap/catalog/index handles and invalidate old
    // cursors/buffers under exclusive maintenance. Verify publication before switching.
    // Storage and WAL must select the same lineage; activation is not a second publication point.
    throw new UnsupportedOperationException(
        "TODO(SM1): switch to the published storage generation");
  }
}
