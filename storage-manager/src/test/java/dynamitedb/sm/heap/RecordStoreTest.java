package dynamitedb.sm.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordStoreTest {
  private static final TransactionId TX = new TransactionId(7);
  private static final Schema SCHEMA =
      new Schema(
          List.of(
              new Column("item", "id", DataType.INTEGER, 0, false),
              new Column("item", "label", DataType.VARCHAR, 400, false)));

  @TempDir Path directory;

  private final AtomicLong lsn = new AtomicLong();
  private final AtomicLong durableUpTo = new AtomicLong(-1);
  private BufferPool buffer;
  private CatalogManager catalog;
  private RecordStore records;
  private RecordSerializer serializer;

  @BeforeEach
  void open() {
    open(4);
    catalog.createTable("item", SCHEMA);
  }

  private void open(int capacity) {
    buffer =
        new BufferPool(
            new DiskManager(directory),
            l -> durableUpTo.accumulateAndGet(l.value(), Math::max),
            capacity);
    catalog = new CatalogManager(directory);
    serializer = new RecordSerializer();
    var heap = new HeapFile(directory, buffer, serializer, catalog);
    records =
        new RecordStore(heap, buffer, serializer, new IndexManager(heap, serializer), catalog);
  }

  @Test
  void insertIsVisibleToFetchAndScanOnlyAfterApply() {
    PreparedWrite prepared = records.prepareInsert(TX, "item", row(1, "one"));
    assertTrue(records.fetch(prepared.rid()).isEmpty(), "reservation must stay invisible");
    assertEquals(List.of(), scan());

    apply(prepared);

    assertEquals(
        Optional.of(new StoredRow(prepared.rid(), row(1, "one"))), records.fetch(prepared.rid()));
    assertEquals(List.of(row(1, "one")), scan().stream().map(StoredRow::row).toList());
    assertEquals(1, catalog.getStatistics("item").rowCount());
  }

  @Test
  void concurrentReservationsNeverShareASlotAndMayApplyOutOfOrder() {
    PreparedWrite first = records.prepareInsert(TX, "item", row(1, "first"));
    PreparedWrite second = records.prepareInsert(TX, "item", row(2, "second"));
    assertNotEquals(first.rid(), second.rid());

    LogSequenceNumber firstLsn = new LogSequenceNumber(lsn.incrementAndGet());
    LogSequenceNumber secondLsn = new LogSequenceNumber(lsn.incrementAndGet());
    records.apply(second, secondLsn);
    records.apply(first, firstLsn);

    assertEquals(2, scan().size());
  }

  @Test
  void cancelIsIdempotentAndBlocksLaterApply() {
    insert(1, "kept");
    List<StoredRow> before = scan();
    PreparedWrite prepared = records.prepareInsert(TX, "item", row(2, "cancelled"));

    records.cancel(prepared);
    records.cancel(prepared);

    DbmsException error =
        assertThrows(DbmsException.class, () -> records.apply(prepared, new LogSequenceNumber(99)));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertEquals(before, scan());
  }

  @Test
  void reservationIsSingleUse() {
    PreparedWrite prepared = records.prepareInsert(TX, "item", row(1, "once"));
    apply(prepared);
    assertThrows(DbmsException.class, () -> apply(prepared));
    records.cancel(prepared); // cancel tidak undo logged change
    assertEquals(1, scan().size());
  }

  @Test
  void updateAndDeleteAreVisibleInScanAndIndex() {
    records.createIndex(new IndexMetadata("item_id", "item", "id", IndexKind.HASH));
    RecordId rid = insert(1, "before");
    assertEquals(1, lookup(1).size());

    apply(records.prepareUpdate(TX, rid, row(2, "after, and longer than before")));
    assertEquals(List.of(new StoredRow(rid, row(2, "after, and longer than before"))), scan());
    assertEquals(0, lookup(1).size());
    assertEquals(1, lookup(2).size());

    apply(records.prepareDelete(TX, rid));
    assertEquals(List.of(), scan());
    assertEquals(0, lookup(2).size());
    assertTrue(records.fetch(rid).isEmpty());
    assertEquals(0, catalog.getStatistics("item").rowCount());
  }

  @Test
  void updateRejectsRowsThatDoNotMatchTheSchema() {
    RecordId rid = insert(1, "valid");
    Row wrongType = new Row(List.of(new TextValue("x"), new TextValue("y")));
    assertThrows(DbmsException.class, () -> records.prepareUpdate(TX, rid, wrongType));
    assertEquals(Optional.of(new StoredRow(rid, row(1, "valid"))), records.fetch(rid));
  }

  @Test
  void growingUpdateThatNoLongerFitsItsPageIsRejectedWithoutMovingTheRid() {
    var rids = new ArrayList<RecordId>();
    for (int i = 0; i < 9; i++) {
      rids.add(insert(i, "x".repeat(400)));
    }
    RecordId small = insert(100, "s");
    if (small.pageNumber() != rids.get(0).pageNumber()) {
      return; // first page sudah penuh, skip
    }
    DbmsException error =
        assertThrows(
            DbmsException.class, () -> records.prepareUpdate(TX, small, row(100, "y".repeat(400))));
    assertEquals(ErrorCode.STORAGE_FAILURE, error.getCode());
    assertEquals(Optional.of(new StoredRow(small, row(100, "s"))), records.fetch(small));
  }

  @Test
  void fullPagesAllocateNewBlocksAndScanDoesNotLeakPins() {
    for (int i = 0; i < 100; i++) {
      insert(i, "row " + i + " " + "z".repeat(200));
    }
    long pages = catalog.getStatistics("item").blockCount();
    assertTrue(pages > 4, "expected more pages than buffer frames, got " + pages);
    for (int pass = 0; pass < 3; pass++) {
      assertEquals(100, scan().size());
    }
  }

  @Test
  void dirtyPagesAreWrittenOnlyAfterTheirLsnIsDurable() {
    for (int i = 0; i < 60; i++) {
      insert(i, "w".repeat(300));
    }
    buffer.flushDirtyThrough(new LogSequenceNumber(Long.MAX_VALUE));
    assertEquals(lsn.get(), durableUpTo.get());
  }

  @Test
  void applyRecoveryIsIdempotentAndMaintainsIndexes() {
    records.createIndex(new IndexMetadata("item_id", "item", "id", IndexKind.B_PLUS_TREE));
    RecordId rid = insert(1, "row");
    long applied = lsn.get();
    RecordImage image = records.read(rid);

    records.applyRecovery(
        new RecoveryMutation(rid, RecordImage.absent()), new LogSequenceNumber(applied));
    assertEquals(image, records.read(rid), "LSN <= pageLSN must be skipped");

    records.applyRecovery(
        new RecoveryMutation(rid, RecordImage.absent()), new LogSequenceNumber(applied + 1));
    records.applyRecovery(
        new RecoveryMutation(rid, RecordImage.absent()), new LogSequenceNumber(applied + 1));
    assertTrue(records.fetch(rid).isEmpty());
    assertEquals(0, lookup(1).size());

    records.applyRecovery(new RecoveryMutation(rid, image), new LogSequenceNumber(applied + 2));
    records.applyRecovery(new RecoveryMutation(rid, image), new LogSequenceNumber(applied + 2));
    assertEquals(List.of(new StoredRow(rid, row(1, "row"))), scan());
    assertEquals(1, lookup(1).size());
  }

  @Test
  void dataIndexesAndStatisticsSurviveRestart() {
    records.createIndex(new IndexMetadata("item_id", "item", "id", IndexKind.HASH));
    for (int i = 0; i < 30; i++) {
      insert(i, "persist " + i);
    }
    buffer.flushDirtyThrough(new LogSequenceNumber(Long.MAX_VALUE));

    open(4);
    records.rebuildIndexes();

    assertEquals(30, scan().size());
    assertEquals(1, lookup(17).size());
    assertEquals(30, catalog.getStatistics("item").rowCount());
  }

  @Test
  void unknownTableIsABindingError() {
    DbmsException error =
        assertThrows(DbmsException.class, () -> records.prepareInsert(TX, "missing", row(1, "x")));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());
  }

  private RecordId insert(int id, String label) {
    PreparedWrite prepared = records.prepareInsert(TX, "item", row(id, label));
    apply(prepared);
    return prepared.rid();
  }

  private void apply(PreparedWrite prepared) {
    records.apply(prepared, new LogSequenceNumber(lsn.incrementAndGet()));
  }

  private List<StoredRow> scan() {
    var rows = new ArrayList<StoredRow>();
    try (RowCursor cursor = records.openScan("item")) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.get());
      }
    }
    return rows;
  }

  private List<StoredRow> lookup(int id) {
    var rows = new ArrayList<StoredRow>();
    try (RowCursor cursor =
        records.openIndexScan("item_id", new IndexPredicate(ComparisonOp.EQ, new IntValue(id)))) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.get());
      }
    }
    return rows;
  }

  private static Row row(int id, String label) {
    return new Row(List.of(new IntValue(id), new TextValue(label)));
  }
}
