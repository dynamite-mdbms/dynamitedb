package dynamitedb.sm.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Pencarian index dengan NULL harus kosong seperti predikat SQL pada scan (issue #68). */
class IndexNullKeyTest {
  private static final TransactionId TX = new TransactionId(1);
  private static final Schema SCHEMA =
      new Schema(
          List.of(
              new Column("reading", "id", DataType.INTEGER, 0, false),
              new Column("reading", "score", DataType.INTEGER, 0, true)));

  @TempDir Path directory;

  private final AtomicLong lsn = new AtomicLong();
  private RecordStore records;

  @BeforeEach
  void setUp() {
    var buffer = new BufferPool(new DiskManager(directory), l -> {}, 8);
    var catalog = new CatalogManager(directory);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(directory, buffer, serializer, catalog);
    records =
        new RecordStore(heap, buffer, serializer, new IndexManager(heap, serializer), catalog);
    catalog.createTable("reading", SCHEMA);
    insert(1, NullValue.INSTANCE);
    insert(2, NullValue.INSTANCE);
    insert(3, new IntValue(10));
  }

  @ParameterizedTest
  @EnumSource(IndexKind.class)
  void equalityWithNullReturnsNoRowsButOtherKeysStillMatch(IndexKind kind) {
    records.createIndex(new IndexMetadata("reading_score", "reading", "score", kind));

    assertEquals(0, count(ComparisonOp.EQ, NullValue.INSTANCE));
    assertEquals(1, count(ComparisonOp.EQ, new IntValue(10)));
  }

  @Test
  void rangeWithNullBoundReturnsNoRows() {
    records.createIndex(
        new IndexMetadata("reading_score", "reading", "score", IndexKind.B_PLUS_TREE));

    for (var operator :
        List.of(ComparisonOp.LT, ComparisonOp.LTE, ComparisonOp.GT, ComparisonOp.GTE)) {
      assertEquals(0, count(operator, NullValue.INSTANCE), operator.name());
    }
    assertEquals(1, count(ComparisonOp.GTE, new IntValue(10)));
  }

  private void insert(int id, Value score) {
    records.apply(
        records.prepareInsert(TX, "reading", new Row(List.of(new IntValue(id), score))),
        new LogSequenceNumber(lsn.incrementAndGet()));
  }

  private int count(ComparisonOp operator, Value key) {
    int rows = 0;
    try (RowCursor cursor =
        records.openIndexScan("reading_score", new IndexPredicate(operator, key))) {
      while (cursor.next().isPresent()) {
        rows++;
      }
    }
    return rows;
  }
}
