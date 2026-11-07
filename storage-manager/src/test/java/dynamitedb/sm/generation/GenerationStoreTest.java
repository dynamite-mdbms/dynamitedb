package dynamitedb.sm.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationStoreTest {
  private static final TransactionId TX = new TransactionId(1);
  private static final Schema SCHEMA =
      new Schema(List.of(new Column("t", "id", DataType.INTEGER, 0, false)));

  @TempDir Path directory;

  private final AtomicLong lsn = new AtomicLong();
  private final AtomicReference<GenerationManifest> published = new AtomicReference<>();
  private RecordStore records;
  private GenerationStore generations;
  private RecordSerializer serializer;

  @BeforeEach
  void open() {
    published.set(manifest(UUID.randomUUID(), 0));
    GenerationManifestStore manifests =
        new GenerationManifestStore() {
          @Override
          public GenerationManifest readActive() {
            return published.get();
          }

          @Override
          public void publishAtomically(GenerationManifest manifest) {
            published.set(manifest);
          }
        };
    var buffer = new BufferPool(new DiskManager(directory), l -> {});
    var catalog = new CatalogManager(directory);
    catalog.createTable("t", SCHEMA);
    serializer = new RecordSerializer();
    var heap = new HeapFile(directory, buffer, serializer, catalog);
    var indexes = new IndexManager(heap, serializer);
    records = new RecordStore(heap, buffer, serializer, indexes, catalog);
    generations = new GenerationStore(directory, manifests, buffer, catalog, indexes, records);
  }

  @Test
  void historicalStageIsIsolatedAndBecomesActiveOnlyAfterPublication() {
    RecordId first = insert(1);
    SnapshotMetadata snapshot = generations.captureSnapshot(boundary(lsn.get()));
    insert(2); // setelah snapshot, tidak boleh masuk historical generation

    UUID stageId = UUID.randomUUID();
    RecoveryStage stage = generations.createStage(snapshot, stageId);
    var replayed = new RecordId(first.tableId(), first.pageNumber(), first.slotNumber() + 1, 0);
    var image = serializer.encode(new Row(List.of(new IntValue(3))), SCHEMA);
    generations.replayInStage(
        stage, new RecoveryMutation(replayed, image), new LogSequenceNumber(100));
    generations.replayInStage(
        stage, new RecoveryMutation(replayed, image), new LogSequenceNumber(100));
    generations.rebuildStageIndexes(stage);
    assertEquals(List.of(1, 2), ids(), "stage must not touch active data");

    SealedGeneration sealed = generations.sealStage(stage, boundary(100));
    assertEquals(stageId, sealed.id());
    assertThrows(
        DbmsException.class,
        () -> generations.switchToPublishedGeneration(manifest(stageId, 100)),
        "unpublished manifest must be rejected");

    published.set(manifest(stageId, 100));
    generations.switchToPublishedGeneration(published.get());
    assertEquals(List.of(1, 3), ids());

    generations.switchToPublishedGeneration(published.get());
    assertEquals(List.of(1, 3), ids(), "activation is idempotent");
    assertEquals(published.get(), generations.readActiveManifest());
  }

  @Test
  void initialGenerationNeedsNoFiles() {
    insert(1);
    generations.switchToPublishedGeneration(published.get());
    assertEquals(List.of(1), ids());
  }

  private RecordId insert(int id) {
    PreparedWrite prepared = records.prepareInsert(TX, "t", new Row(List.of(new IntValue(id))));
    records.apply(prepared, new LogSequenceNumber(lsn.incrementAndGet()));
    return prepared.rid();
  }

  private List<Integer> ids() {
    var ids = new ArrayList<Integer>();
    try (RowCursor cursor = records.openScan("t")) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        ids.add(((IntValue) next.get().row().values().get(0)).value());
      }
    }
    ids.sort(null);
    return ids;
  }

  private static RecoveryBoundary boundary(long value) {
    return new RecoveryBoundary(new LogSequenceNumber(value), Optional.empty());
  }

  private static GenerationManifest manifest(UUID id, long value) {
    var at = new LogSequenceNumber(value);
    return new GenerationManifest(
        id, new UUID(0, 1), at, new RecoveryHighWater(at, TX, 0, Optional.empty()));
  }
}
