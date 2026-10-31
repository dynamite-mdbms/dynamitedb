package dynamitedb.sm.generation;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Snapshot, stage recovery historis, dan aktivasi generasi storage (FR-05). */
public final class GenerationStore {
  private static final UUID MOCK_LINEAGE = new UUID(0L, 0L);

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
    // TODO: snapshot heap dan katalog yang konsisten saat maintenance
    return new SnapshotMetadata(
        UUID.randomUUID(), MOCK_LINEAGE, boundary.inclusiveLsn(), Instant.now());
  }

  public RecoveryStage createStage(SnapshotMetadata snapshot, UUID id) {
    // TODO: ruang kerja terisolasi dari snapshot tanpa menyentuh data aktif
    return new RecoveryStage(id);
  }

  public void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn) {
    // TODO: terapkan perubahan committed hanya di stage
  }

  public void rebuildStageIndexes(RecoveryStage stage) {
    // TODO: bangun ulang index dari heap stage
  }

  public SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
    // TODO: validasi dan segel stage, publikasi tetap oleh FRM
    return new SealedGeneration(stage.id(), boundary);
  }

  public GenerationManifest readActiveManifest() {
    // TODO: baca dari GenerationManifestStore, jangan simpan manifest kedua
    LogSequenceNumber start = new LogSequenceNumber(0);
    return new GenerationManifest(
        MOCK_LINEAGE,
        MOCK_LINEAGE,
        start,
        new RecoveryHighWater(start, new TransactionId(0), 0, Optional.empty()));
  }

  public void switchToPublishedGeneration(GenerationManifest manifest) {
    // TODO: ganti handle heap, katalog, index, dan buffer ke generasi baru
  }
}
