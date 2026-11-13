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
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Index teks harus memberi baris yang sama dengan table scan (issue #58). */
class IndexTextKeyTest {
  private static final TransactionId TX = new TransactionId(1);
  private static final Schema SCHEMA =
      new Schema(
          List.of(
              new Column("student", "student_id", DataType.INTEGER, 0, false),
              new Column("student", "name", DataType.VARCHAR, 50, false)));
  private static final String INDEX = "idx_student_name";

  @TempDir Path directory;

  private final AtomicLong lsn = new AtomicLong();
  private BufferPool buffer;
  private CatalogManager catalog;
  private RecordStore records;
  private final Map<Integer, RecordId> rids = new TreeMap<>();

  @BeforeEach
  void setUp() {
    open();
    catalog.createTable("student", SCHEMA);
    insert(9100, "AuditA");
    insert(9101, "AuditA\t");
    insert(9102, "AuditA  ");
    insert(9103, "AuditB");
    insert(9104, "Audit");
    for (int i = 0; i < 180; i++) {
      insert(10_000 + i, "filler " + i);
    }
  }

  @ParameterizedTest
  @EnumSource(IndexKind.class)
  void equalityMatchesTableScanBeforeAndAfterIndexMaintenanceAndRestart(IndexKind kind) {
    List<Integer> before = scanIds(ComparisonOp.EQ, "AuditA");
    assertEquals(List.of(9100, 9101, 9102), before);

    records.createIndex(new IndexMetadata(INDEX, "student", "name", kind));
    assertEquals(before, indexIds(ComparisonOp.EQ, "AuditA"));
    assertEquals(before, indexIds(ComparisonOp.EQ, "AuditA\t"));

    apply(records.prepareDelete(TX, rids.get(9101)));
    assertEquals(List.of(9100, 9102), scanIds(ComparisonOp.EQ, "AuditA"));
    assertEquals(scanIds(ComparisonOp.EQ, "AuditA"), indexIds(ComparisonOp.EQ, "AuditA"));

    buffer.flushDirtyThrough(new LogSequenceNumber(Long.MAX_VALUE));
    open();
    records.rebuildIndexes();
    assertEquals(scanIds(ComparisonOp.EQ, "AuditA "), indexIds(ComparisonOp.EQ, "AuditA "));
  }

  @ParameterizedTest
  @EnumSource(
      value = ComparisonOp.class,
      names = {"LT", "LTE", "GT", "GTE"})
  void bPlusTreeRangesMatchTableScan(ComparisonOp operator) {
    records.createIndex(new IndexMetadata(INDEX, "student", "name", IndexKind.B_PLUS_TREE));
    assertEquals(scanIds(operator, "AuditA\t"), indexIds(operator, "AuditA\t"));
  }

  private void open() {
    buffer = new BufferPool(new DiskManager(directory), l -> {}, 8);
    catalog = new CatalogManager(directory);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(directory, buffer, serializer, catalog);
    records =
        new RecordStore(heap, buffer, serializer, new IndexManager(heap, serializer), catalog);
  }

  private void insert(int id, String name) {
    PreparedWrite prepared =
        records.prepareInsert(
            TX, "student", new Row(List.of(new IntValue(id), new TextValue(name))));
    apply(prepared);
    rids.put(id, prepared.rid());
  }

  private void apply(PreparedWrite prepared) {
    records.apply(prepared, new LogSequenceNumber(lsn.incrementAndGet()));
  }

  // aturan perbandingan teks yang dipakai QP saat scan
  private List<Integer> scanIds(ComparisonOp operator, String bound) {
    var ids = new ArrayList<Integer>();
    try (RowCursor cursor = records.openScan("student")) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        String name = ((TextValue) next.get().row().values().get(1)).value();
        int cmp = name.stripTrailing().compareTo(bound.stripTrailing());
        boolean match =
            switch (operator) {
              case EQ -> cmp == 0;
              case LT -> cmp < 0;
              case LTE -> cmp <= 0;
              case GT -> cmp > 0;
              case GTE -> cmp >= 0;
              case NEQ -> cmp != 0;
            };
        if (match) {
          ids.add(id(next.get()));
        }
      }
    }
    return ids.stream().sorted().toList();
  }

  private List<Integer> indexIds(ComparisonOp operator, String bound) {
    var ids = new ArrayList<Integer>();
    try (RowCursor cursor =
        records.openIndexScan(INDEX, new IndexPredicate(operator, new TextValue(bound)))) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        ids.add(id(next.get()));
      }
    }
    return ids.stream().sorted().toList();
  }

  private static int id(StoredRow row) {
    return ((IntValue) row.row().values().get(0)).value();
  }
}
