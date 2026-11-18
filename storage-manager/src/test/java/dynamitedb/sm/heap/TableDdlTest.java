package dynamitedb.sm.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TableDefinition;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableDdlTest {
  private static final TransactionId TX = new TransactionId(3);

  @TempDir Path directory;

  private final AtomicLong lsn = new AtomicLong();
  private BufferPool buffer;
  private CatalogManager catalog;
  private RecordStore records;

  @BeforeEach
  void open() {
    buffer = new BufferPool(new DiskManager(directory), l -> {}, 8);
    catalog = new CatalogManager(directory);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(directory, buffer, serializer, catalog);
    records =
        new RecordStore(heap, buffer, serializer, new IndexManager(heap, serializer), catalog);
    records.rebuildIndexes();
  }

  @Test
  void createdTableAcceptsRowsAndItsPrimaryKeyIndexMatchesScan() {
    records.createTable(dept());
    for (int i = 1; i <= 40; i++) {
      insert("dept", row(i, "dept " + i));
    }
    assertEquals(40, scan("dept").size());
    assertEquals(List.of(row(7, "dept 7")), lookup("pk_dept", 7));
    assertEquals(40, catalog.getStatistics("dept").rowCount());

    var nullKey = new Row(List.of(NullValue.INSTANCE, new TextValue("x")));
    assertThrows(DbmsException.class, () -> records.prepareInsert(TX, "dept", nullKey));
  }

  @Test
  void failedDdlLeavesExistingTablesAndDataIntact() {
    records.createTable(dept());
    records.createTable(emp());
    insert("dept", row(1, "kept"));
    List<Row> before = scan("dept");

    assertThrows(DbmsException.class, () -> records.createTable(dept()));
    assertThrows(DbmsException.class, () -> records.dropTable("dept", DropBehavior.RESTRICT));
    assertThrows(DbmsException.class, () -> records.dropTable("missing", DropBehavior.CASCADE));

    assertEquals(before, scan("dept"));
    assertEquals(List.of(row(1, "kept")), lookup("pk_dept", 1));
    assertEquals(List.of("dept", "emp"), catalog.listTables());
  }

  @Test
  void dropRemovesRowsIndexAndStatisticsAndTheNameCanBeReused() {
    records.createTable(dept());
    insert("dept", row(1, "old"));
    long oldId = catalog.getTableId("dept");

    records.dropTable("dept", DropBehavior.RESTRICT);
    assertEquals(
        ErrorCode.SQL_BIND,
        assertThrows(DbmsException.class, () -> lookup("pk_dept", 1)).getCode());

    records.createTable(dept());
    assertTrue(catalog.getTableId("dept") > oldId, "table IDs are never reused");
    assertEquals(List.of(), scan("dept"));
    assertEquals(List.of(), lookup("pk_dept", 1));
    assertEquals(0, catalog.getStatistics("dept").rowCount());
    assertEquals(0, catalog.getStatistics("dept").blockCount());
  }

  @Test
  void redoOfChangesLoggedBeforeDropIsSkipped() {
    records.createTable(dept());
    RecordId rid = insert("dept", row(1, "logged"));
    RecordImage image = records.read(rid);
    records.dropTable("dept", DropBehavior.RESTRICT);

    records.applyRecovery(new RecoveryMutation(rid, image), new LogSequenceNumber(lsn.get() + 1));

    var unknown = new RecordId(999, rid.pageNumber(), 0, 0);
    var error =
        assertThrows(
            DbmsException.class,
            () ->
                records.applyRecovery(
                    new RecoveryMutation(unknown, image), new LogSequenceNumber(99)));
    assertEquals(ErrorCode.RECOVERY_FAILURE, error.getCode());
  }

  @Test
  void ddlSurvivesRestartAndCrashBetweenCatalogAndHeapIsCleanedUp() throws Exception {
    records.createTable(dept());
    records.createTable(emp());
    insert("dept", row(1, "a"));
    insert("emp", new Row(List.of(new IntValue(10), new IntValue(1))));
    buffer.flushDirtyThrough(new LogSequenceNumber(Long.MAX_VALUE));
    long heapBefore = Files.size(directory.resolve("heap.dir"));

    // Crash setelah catalog mencatat DROP tetapi sebelum heap.dir diperbarui.
    catalog.dropTable("emp", DropBehavior.RESTRICT);
    open();

    assertEquals(List.of("dept"), catalog.listTables());
    assertEquals(List.of(row(1, "a")), lookup("pk_dept", 1));
    assertTrue(Files.size(directory.resolve("heap.dir")) < heapBefore, "orphaned pages pruned");
  }

  private RecordId insert(String table, Row row) {
    PreparedWrite prepared = records.prepareInsert(TX, table, row);
    records.apply(prepared, new LogSequenceNumber(lsn.incrementAndGet()));
    return prepared.rid();
  }

  private List<Row> scan(String table) {
    var rows = new ArrayList<Row>();
    try (RowCursor cursor = records.openScan(table)) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.get().row());
      }
    }
    return rows;
  }

  private List<Row> lookup(String index, int key) {
    var rows = new ArrayList<Row>();
    try (RowCursor cursor =
        records.openIndexScan(index, new IndexPredicate(ComparisonOp.EQ, new IntValue(key)))) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.map(StoredRow::row).orElseThrow());
      }
    }
    return rows;
  }

  private static Row row(int id, String name) {
    return new Row(List.of(new IntValue(id), new TextValue(name)));
  }

  private static TableDefinition dept() {
    return new TableDefinition(
        "dept",
        new Schema(
            List.of(
                new Column("dept", "id", DataType.INTEGER, 0, true),
                new Column("dept", "name", DataType.VARCHAR, 30, true))),
        Optional.of(new PrimaryKey(List.of("id"))),
        List.of());
  }

  private static TableDefinition emp() {
    return new TableDefinition(
        "emp",
        new Schema(
            List.of(
                new Column("emp", "id", DataType.INTEGER, 0, false),
                new Column("emp", "dept_id", DataType.INTEGER, 0, true))),
        Optional.of(new PrimaryKey(List.of("id"))),
        List.of(new ForeignKey("emp", List.of("dept_id"), "dept", List.of("id"))));
  }
}
