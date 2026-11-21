package dynamitedb.sm.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableDefinition;
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

class GenerationDdlTest {
  private static final TransactionId TX = new TransactionId(1);

  @TempDir Path directory;

  private final AtomicLong lsn = new AtomicLong();
  private final AtomicReference<GenerationManifest> published = new AtomicReference<>();
  private CatalogManager catalog;
  private RecordStore records;
  private GenerationStore generations;

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
    catalog = new CatalogManager(directory);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(directory, buffer, serializer, catalog);
    var indexes = new IndexManager(heap, serializer);
    records = new RecordStore(heap, buffer, serializer, indexes, catalog);
    generations = new GenerationStore(directory, manifests, buffer, catalog, indexes, records);
  }

  @Test
  void stageReplaysTablesCreatedAfterTheSnapshotOntoNewPages() {
    records.createTable(table("old"));
    insert("old", 1);
    SnapshotMetadata snapshot = generations.captureSnapshot(boundary(lsn.get()));

    records.createTable(table("fresh"));
    var mutations = new ArrayList<RecoveryMutation>();
    var lsns = new ArrayList<Long>();
    for (int i = 1; i <= 300; i++) {
      PreparedWrite prepared = insert("fresh", i);
      mutations.add(new RecoveryMutation(prepared.rid(), prepared.after()));
      lsns.add(lsn.get());
    }
    records.dropTable("old", DropBehavior.RESTRICT);

    UUID id = UUID.randomUUID();
    RecoveryStage stage = generations.createStage(snapshot, id);
    for (int i = 0; i < mutations.size(); i++) {
      generations.replayInStage(stage, mutations.get(i), new LogSequenceNumber(lsns.get(i)));
    }
    generations.rebuildStageIndexes(stage);
    generations.sealStage(stage, boundary(lsn.get()));
    published.set(manifest(id, lsn.get()));
    generations.switchToPublishedGeneration(published.get());

    assertEquals(List.of("fresh"), catalog.listTables(), "stage uses current definitions");
    assertEquals(300, count("fresh"));
    assertEquals(300, catalog.getStatistics("fresh").rowCount());
  }

  private PreparedWrite insert(String table, int id) {
    PreparedWrite prepared =
        records.prepareInsert(TX, table, new Row(List.of(new IntValue(id), new IntValue(id * 10))));
    records.apply(prepared, new LogSequenceNumber(lsn.incrementAndGet()));
    return prepared;
  }

  private int count(String table) {
    int rows = 0;
    try (RowCursor cursor = records.openScan(table)) {
      while (cursor.next().isPresent()) {
        rows++;
      }
    }
    return rows;
  }

  private static TableDefinition table(String name) {
    return new TableDefinition(
        name,
        new Schema(
            List.of(
                new Column(name, "id", DataType.INTEGER, 0, false),
                new Column(name, "v", DataType.INTEGER, 0, true))),
        Optional.of(new PrimaryKey(List.of("id"))),
        List.of());
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
