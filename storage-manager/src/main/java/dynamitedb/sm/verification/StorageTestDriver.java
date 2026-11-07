package dynamitedb.sm.verification;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.catalog.SampleDatabaseLoader;
import dynamitedb.sm.catalog.StorageCatalogAdapter;
import dynamitedb.sm.facade.StorageManagerImpl;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** SM test driver lewat public ports (SM-15); run: ./gradlew :storage-manager:smDriver. */
public final class StorageTestDriver {
  private static final TransactionId DRIVER_TX = new TransactionId(1_000_000);
  private static final Set<ErrorCode> REJECTION_CODES =
      EnumSet.of(ErrorCode.SQL_BIND, ErrorCode.STORAGE_FAILURE, ErrorCode.INVALID_STATE);

  private final TupleReader reader;
  private final WriteStore writes;
  private final IndexAdmin indexes;
  private final CatalogAccess catalog;
  private final ChangeLogger logger;
  private final String suffix = UUID.randomUUID().toString().substring(0, 8);

  /** Pakai local LSN counter, untuk standalone storage tanpa WAL. */
  public StorageTestDriver(
      TupleReader reader, WriteStore writes, IndexAdmin indexes, CatalogAccess catalog) {
    this(reader, writes, indexes, catalog, counterLogger(new AtomicLong()));
  }

  /** Pakai real ChangeLogger (CCM/FRM) sebagai LSN source. */
  public StorageTestDriver(
      TupleReader reader,
      WriteStore writes,
      IndexAdmin indexes,
      CatalogAccess catalog,
      ChangeLogger logger) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  public TestReport runAll() {
    var results = new ArrayList<TestResult>();
    results.add(
        run("Catalog: >= 3 tables cover INTEGER, FLOAT, CHAR, VARCHAR", this::catalogTypes));
    results.add(run("Scan: each sample table has >= 50 rows with RID and all columns", this::scan));
    results.add(run("Scan: reading twice returns the same rows (read-only)", this::readOnly));
    results.add(run("Fetch: every scanned RID returns the same row", this::fetchAll));
    results.add(run("Fetch: unknown RID returns empty", this::fetchMissing));
    results.add(run("Insert: new RID visible to fetch and scan", this::insertVisible));
    results.add(run("Insert: invalid row rejected, data unchanged", this::insertInvalid));
    results.add(run("Insert: unknown table rejected with SQL_BIND", this::insertUnknownTable));
    results.add(run("Cancel: prepared insert never visible, cancel idempotent", this::cancel));
    results.add(run("Insert: full page allocates a new block", this::newBlock));
    results.add(run("Update: new values visible at the same RID", this::update));
    results.add(run("Update: row not matching schema rejected (SM-06)", this::updateInvalid));
    results.add(run("Delete: row gone from fetch and scan", this::delete));
    results.add(run("Index: HASH equality matches table scan", this::hashIndex));
    results.add(run("Index: B+Tree range (<, <=, >, >=) matches table scan", this::rangeIndex));
    results.add(run("Index: range on HASH rejected (UNSUPPORTED_OPERATION)", this::hashRange));
    results.add(run("Index: unknown column rejected with SQL_BIND", this::indexUnknownColumn));
    results.add(run("Index: maintained by insert, update and delete", this::indexMaintenance));
    results.add(run("Statistics: rowCount follows insert and delete", this::statistics));
    results.add(run("Delete: clearing a whole table empties scan, index and stats", this::clear));
    return new TestReport(results);
  }

  // ---------------------------------------------------------------- scenarios

  private String catalogTypes() {
    List<String> tables = catalog.listTables();
    require(tables.size() >= 3, "only " + tables.size() + " tables");
    Set<DataType> types = EnumSet.noneOf(DataType.class);
    tables.forEach(t -> catalog.getSchema(t).columns().forEach(c -> types.add(c.dataType())));
    require(types.equals(EnumSet.allOf(DataType.class)), "types " + types);
    return tables.size() + " tables, types " + types;
  }

  private String scan() {
    var counts = new ArrayList<String>();
    for (String table : List.of("student", "course", "enrollment")) {
      List<StoredRow> rows = scanAll(table);
      int columns = catalog.getSchema(table).columns().size();
      long tableId = catalog.getTableId(table);
      require(rows.size() >= 50, table + " has " + rows.size() + " rows");
      for (StoredRow row : rows) {
        require(row.rid().tableId() == tableId, "wrong tableId in " + row.rid());
        require(row.row().values().size() == columns, "wrong column count at " + row.rid());
      }
      counts.add(table + "=" + rows.size());
    }
    return String.join(", ", counts);
  }

  private String readOnly() {
    List<StoredRow> first = scanAll("enrollment");
    List<StoredRow> second = scanAll("enrollment");
    require(first.equals(second), "scan results differ");
    return first.size() + " rows identical";
  }

  private String fetchAll() {
    List<StoredRow> rows = scanAll("student");
    for (StoredRow row : rows) {
      Optional<StoredRow> fetched = reader.fetch(row.rid(), DRIVER_TX);
      require(fetched.equals(Optional.of(row)), "fetch mismatch at " + row.rid());
    }
    return rows.size() + " rows fetched";
  }

  private String fetchMissing() {
    StoredRow any = scanAll("student").get(0);
    RecordId rid = any.rid();
    var staleIncarnation =
        new RecordId(rid.tableId(), rid.pageNumber(), rid.slotNumber(), rid.generation() + 1);
    var unknownTable = new RecordId(Long.MAX_VALUE, 0, 0, 0);
    require(reader.fetch(staleIncarnation, DRIVER_TX).isEmpty(), "stale incarnation visible");
    require(reader.fetch(unknownTable, DRIVER_TX).isEmpty(), "unknown table visible");
    return "stale incarnation and unknown table both empty";
  }

  private String insertVisible() {
    int before = scanAll("student").size();
    Row row = student(9001, "Driver Insert", new FloatValue(3.5), "A");
    RecordId rid = insert("student", row);
    require(reader.fetch(rid, DRIVER_TX).map(StoredRow::row).equals(Optional.of(row)), "fetch");
    require(scanAll("student").size() == before + 1, "scan count did not grow");
    return "RID " + rid;
  }

  private String insertInvalid() {
    List<StoredRow> before = scanAll("student");
    Row tooLong = student(9002, "x".repeat(51), new FloatValue(1.0), "A");
    Row wrongArity = new Row(List.of(new IntValue(9003)));
    Row nullNotNull = student(9004, null, new FloatValue(1.0), "A");
    require(rejected(() -> writes.prepareInsert(DRIVER_TX, "student", tooLong)), "VARCHAR(50)");
    require(rejected(() -> writes.prepareInsert(DRIVER_TX, "student", wrongArity)), "arity");
    require(rejected(() -> writes.prepareInsert(DRIVER_TX, "student", nullNotNull)), "NOT NULL");
    require(scanAll("student").equals(before), "data changed");
    return "too long, wrong arity and NULL in NOT NULL column rejected";
  }

  private String insertUnknownTable() {
    Row row = student(1, "x", NullValue.INSTANCE, "A");
    require(
        errorCode(() -> writes.prepareInsert(DRIVER_TX, "no_such_table", row))
            .equals(Optional.of(ErrorCode.SQL_BIND)),
        "expected SQL_BIND");
    require(
        errorCode(() -> reader.openTableScan("no_such_table", DRIVER_TX))
            .equals(Optional.of(ErrorCode.SQL_BIND)),
        "scan expected SQL_BIND");
    return "prepareInsert and openTableScan both SQL_BIND";
  }

  private String cancel() {
    List<StoredRow> before = scanAll("student");
    PreparedWrite prepared =
        writes.prepareInsert(DRIVER_TX, "student", student(9005, "Cancelled", null, "B"));
    require(reader.fetch(prepared.rid(), DRIVER_TX).isEmpty(), "reservation visible");
    writes.cancelPrepared(prepared);
    writes.cancelPrepared(prepared);
    require(
        errorCode(() -> writes.applyWrite(prepared, new LogSequenceNumber(1)))
            .equals(Optional.of(ErrorCode.INVALID_STATE)),
        "apply after cancel accepted");
    require(scanAll("student").equals(before), "data changed");
    return "prepare, cancel twice, apply rejected, data unchanged";
  }

  private String newBlock() {
    long blocksBefore = catalog.getStatistics("course").blockCount();
    int rowsBefore = scanAll("course").size();
    int inserted = 0;
    while (catalog.getStatistics("course").blockCount() == blocksBefore && inserted < 500) {
      insert(
          "course",
          new Row(
              List.of(
                  new IntValue(5000 + inserted),
                  new TextValue("Driver filler course number " + inserted),
                  new IntValue(3),
                  new TextValue("DR" + String.format("%04d", inserted % 10000)))));
      inserted++;
    }
    long blocksAfter = catalog.getStatistics("course").blockCount();
    require(blocksAfter > blocksBefore, "block count stayed " + blocksBefore);
    require(scanAll("course").size() == rowsBefore + inserted, "scan lost rows");
    return inserted + " inserts, blocks " + blocksBefore + " -> " + blocksAfter;
  }

  private String update() {
    RecordId rid = insert("student", student(9006, "Before Update", null, "C"));
    Row after = student(9006, "After Update With Longer Name", new FloatValue(3.75), "D");
    apply(writes.prepareUpdate(DRIVER_TX, rid, after));
    require(reader.fetch(rid, DRIVER_TX).map(StoredRow::row).equals(Optional.of(after)), "fetch");
    require(
        scanAll("student").stream().anyMatch(r -> r.rid().equals(rid) && r.row().equals(after)),
        "scan does not show update");
    return "RID " + rid + " updated in place";
  }

  private String updateInvalid() {
    StoredRow target = scanAll("student").get(0);
    Row wrongType =
        new Row(
            List.of(
                new TextValue("not an integer"),
                new TextValue("x"),
                NullValue.INSTANCE,
                new TextValue("A")));
    require(rejected(() -> writes.prepareUpdate(DRIVER_TX, target.rid(), wrongType)), "type");
    require(reader.fetch(target.rid(), DRIVER_TX).equals(Optional.of(target)), "row was changed");
    return "type mismatch rejected, row unchanged";
  }

  private String delete() {
    RecordId rid = insert("student", student(9007, "To Delete", null, "A"));
    apply(writes.prepareDelete(DRIVER_TX, rid));
    require(reader.fetch(rid, DRIVER_TX).isEmpty(), "fetch still returns row");
    require(scanAll("student").stream().noneMatch(r -> r.rid().equals(rid)), "scan shows row");
    require(rejected(() -> writes.prepareDelete(DRIVER_TX, rid)), "double delete accepted");
    return "RID " + rid + " deleted";
  }

  private String hashIndex() {
    String name = "drv_student_id_" + suffix;
    indexes.createIndex(name, "student", "student_id", IndexKind.HASH);
    require(
        catalog.listIndexes("student").stream().anyMatch(i -> i.name().equals(name)),
        "index not in catalog");
    for (int id : List.of(1, 7, 50, 777_777)) {
      IndexPredicate predicate = new IndexPredicate(ComparisonOp.EQ, new IntValue(id));
      require(
          sameRows(indexScan(name, predicate), filter("student", 0, predicate)),
          "mismatch for student_id = " + id);
    }
    return "EQ on 1, 7, 50 and a missing key match scan";
  }

  private String rangeIndex() {
    String name = "drv_enrollment_score_" + suffix;
    indexes.createIndex(name, "enrollment", "score", IndexKind.B_PLUS_TREE);
    var checked = new ArrayList<String>();
    for (ComparisonOp op :
        List.of(
            ComparisonOp.LT,
            ComparisonOp.LTE,
            ComparisonOp.GT,
            ComparisonOp.GTE,
            ComparisonOp.EQ)) {
      IndexPredicate predicate = new IndexPredicate(op, new FloatValue(80.0));
      List<StoredRow> viaIndex = indexScan(name, predicate);
      require(sameRows(viaIndex, filter("enrollment", 2, predicate)), "mismatch for score " + op);
      checked.add(op + "=" + viaIndex.size());
    }
    return "score vs 80.0: " + String.join(", ", checked);
  }

  private String hashRange() {
    String name = "drv_course_id_" + suffix;
    indexes.createIndex(name, "course", "course_id", IndexKind.HASH);
    require(
        errorCode(
                () ->
                    reader.openIndexScan(
                        name, new IndexPredicate(ComparisonOp.GT, new IntValue(3)), DRIVER_TX))
            .equals(Optional.of(ErrorCode.UNSUPPORTED_OPERATION)),
        "expected UNSUPPORTED_OPERATION");
    return "GT on HASH rejected";
  }

  private String indexUnknownColumn() {
    String name = "drv_bad_" + suffix;
    require(
        errorCode(() -> indexes.createIndex(name, "student", "no_such_column", IndexKind.HASH))
            .equals(Optional.of(ErrorCode.SQL_BIND)),
        "expected SQL_BIND");
    require(
        catalog.listIndexes("student").stream().noneMatch(i -> i.name().equals(name)),
        "half-built index published");
    return "rejected and not published";
  }

  private String indexMaintenance() {
    String name = "drv_student_id_" + suffix;
    IndexPredicate key1 = new IndexPredicate(ComparisonOp.EQ, new IntValue(9100));
    IndexPredicate key2 = new IndexPredicate(ComparisonOp.EQ, new IntValue(9101));
    int base1 = indexScan(name, key1).size();
    int base2 = indexScan(name, key2).size();
    RecordId rid = insert("student", student(9100, "Indexed", null, "A"));
    require(indexScan(name, key1).size() == base1 + 1, "insert not indexed");
    apply(writes.prepareUpdate(DRIVER_TX, rid, student(9101, "Indexed", null, "A")));
    require(indexScan(name, key1).size() == base1, "old key still indexed");
    require(indexScan(name, key2).size() == base2 + 1, "new key not indexed");
    apply(writes.prepareDelete(DRIVER_TX, rid));
    require(indexScan(name, key2).size() == base2, "deleted row still indexed");
    return "insert, update and delete reflected in " + name;
  }

  /** Clear table (SM-07) = DELETE tanpa WHERE: satu prepareDelete per row lewat WriteStore. */
  private String clear() {
    List<StoredRow> rows = scanAll("course");
    for (StoredRow row : rows) {
      apply(writes.prepareDelete(DRIVER_TX, row.rid()));
    }
    require(scanAll("course").isEmpty(), "scan still returns rows");
    require(catalog.getStatistics("course").rowCount() == 0, "rowCount not 0");
    IndexPredicate any = new IndexPredicate(ComparisonOp.EQ, new IntValue(1));
    require(indexScan("drv_course_id_" + suffix, any).isEmpty(), "index still returns rows");
    return rows.size() + " rows deleted from course";
  }

  private String statistics() {
    long before = catalog.getStatistics("enrollment").rowCount();
    RecordId rid =
        insert(
            "enrollment",
            new Row(
                List.of(
                    new IntValue(1), new IntValue(2), new FloatValue(99.5), new TextValue("C"))));
    long afterInsert = catalog.getStatistics("enrollment").rowCount();
    apply(writes.prepareDelete(DRIVER_TX, rid));
    long afterDelete = catalog.getStatistics("enrollment").rowCount();
    require(afterInsert == before + 1, "insert: " + before + " -> " + afterInsert);
    require(afterDelete == before, "delete: " + afterInsert + " -> " + afterDelete);
    return "rowCount " + before + " -> " + afterInsert + " -> " + afterDelete;
  }

  // ---------------------------------------------------------------- helpers

  private RecordId insert(String table, Row row) {
    PreparedWrite prepared = writes.prepareInsert(DRIVER_TX, table, row);
    apply(prepared);
    return prepared.rid();
  }

  private void apply(PreparedWrite prepared) {
    LogSequenceNumber lsn;
    try {
      lsn = logger.recordChange(prepared.tx(), prepared);
    } catch (RuntimeException e) {
      writes.cancelPrepared(prepared);
      throw e;
    }
    writes.applyWrite(prepared, lsn);
  }

  private List<StoredRow> scanAll(String table) {
    try (RowCursor cursor = reader.openTableScan(table, DRIVER_TX)) {
      return drain(cursor);
    }
  }

  private List<StoredRow> indexScan(String index, IndexPredicate predicate) {
    try (RowCursor cursor = reader.openIndexScan(index, predicate, DRIVER_TX)) {
      return drain(cursor);
    }
  }

  private static List<StoredRow> drain(RowCursor cursor) {
    var rows = new ArrayList<StoredRow>();
    for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
      rows.add(next.get());
    }
    return rows;
  }

  private List<StoredRow> filter(String table, int column, IndexPredicate predicate) {
    double bound = number(predicate.value());
    Predicate<Double> test =
        switch (predicate.operator()) {
          case EQ -> v -> v == bound;
          case LT -> v -> v < bound;
          case LTE -> v -> v <= bound;
          case GT -> v -> v > bound;
          case GTE -> v -> v >= bound;
          case NEQ -> v -> v != bound;
        };
    return scanAll(table).stream()
        .filter(r -> !(r.row().values().get(column) instanceof NullValue))
        .filter(r -> test.test(number(r.row().values().get(column))))
        .toList();
  }

  private static double number(Value value) {
    if (value instanceof IntValue i) {
      return i.value();
    }
    if (value instanceof FloatValue f) {
      return f.value();
    }
    throw new IllegalArgumentException("not numeric: " + value);
  }

  private static boolean sameRows(List<StoredRow> a, List<StoredRow> b) {
    var counts = new HashMap<StoredRow, Integer>();
    a.forEach(r -> counts.merge(r, 1, Integer::sum));
    b.forEach(r -> counts.merge(r, -1, Integer::sum));
    return a.size() == b.size() && counts.values().stream().allMatch(c -> c == 0);
  }

  private static Row student(int id, String name, Value gpa, String grade) {
    var values = new ArrayList<Value>();
    values.add(new IntValue(id));
    values.add(name == null ? NullValue.INSTANCE : new TextValue(name));
    values.add(gpa == null ? NullValue.INSTANCE : gpa);
    values.add(new TextValue(grade));
    return new Row(values);
  }

  private static boolean rejected(Runnable action) {
    return errorCode(action).map(REJECTION_CODES::contains).orElse(false);
  }

  private static Optional<ErrorCode> errorCode(Runnable action) {
    try {
      action.run();
      return Optional.empty();
    } catch (DbmsException e) {
      return Optional.of(e.getCode());
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static TestResult run(String name, Supplier<String> scenario) {
    try {
      return new TestResult(true, name + " -- " + scenario.get());
    } catch (RuntimeException e) {
      return new TestResult(
          false, name + " -- " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static ChangeLogger counterLogger(AtomicLong counter) {
    return (tx, write) -> new LogSequenceNumber(counter.incrementAndGet());
  }

  // ---------------------------------------------------------------- standalone program

  /** Standalone run: load sample DB, run scenarios, cek data setelah restart. */
  public static void main(String[] args) throws IOException {
    Path dir =
        args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("dynamitedb-sm-driver");
    var lsn = new AtomicLong();
    ChangeLogger logger = counterLogger(lsn);
    System.out.println("DynamiteDB Storage Manager driver (SM-15), data: " + dir.toAbsolutePath());

    var buffer = new BufferPool(new DiskManager(dir), l -> {});
    var catalog = new CatalogManager(dir);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(dir, buffer, serializer, catalog);
    var indexManager = new IndexManager(heap, serializer);
    var records = new RecordStore(heap, buffer, serializer, indexManager, catalog);
    var storage = new StorageManagerImpl(records, catalog, indexManager);
    records.rebuildIndexes();
    new SampleDatabaseLoader(storage, logger, simpleTransactions()).loadIfAbsent(catalog);

    var results =
        new ArrayList<>(
            new StorageTestDriver(
                    storage, storage, storage, new StorageCatalogAdapter(catalog), logger)
                .runAll()
                .results());

    Map<String, Integer> counts = new HashMap<>();
    for (String table : catalog.listTables()) {
      try (RowCursor cursor = storage.openTableScan(table, DRIVER_TX)) {
        counts.put(table, drain(cursor).size());
      }
    }
    buffer.flushDirtyThrough(new LogSequenceNumber(Long.MAX_VALUE));
    results.add(run("Restart: data persists after reopening storage", () -> reopen(dir, counts)));

    int passed = 0;
    for (TestResult result : results) {
      System.out.println((result.passed() ? "[PASS] " : "[FAIL] ") + result.message());
      passed += result.passed() ? 1 : 0;
    }
    System.out.println(passed + "/" + results.size() + " scenarios passed");
    if (passed != results.size()) {
      System.exit(1);
    }
  }

  private static String reopen(Path dir, Map<String, Integer> expected) {
    var buffer = new BufferPool(new DiskManager(dir), l -> {});
    var catalog = new CatalogManager(dir);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(dir, buffer, serializer, catalog);
    var indexManager = new IndexManager(heap, serializer);
    var records = new RecordStore(heap, buffer, serializer, indexManager, catalog);
    var storage = new StorageManagerImpl(records, catalog, indexManager);
    records.rebuildIndexes();
    for (var entry : expected.entrySet()) {
      try (RowCursor cursor = storage.openTableScan(entry.getKey(), DRIVER_TX)) {
        int actual = drain(cursor).size();
        require(
            actual == entry.getValue(), entry.getKey() + ": " + actual + " != " + entry.getValue());
      }
    }
    return "row counts identical after restart " + expected;
  }

  private static TransactionManager simpleTransactions() {
    var next = new AtomicLong();
    var status = new HashMap<TransactionId, TransactionStatus>();
    return new TransactionManager() {
      @Override
      public TransactionId begin() {
        var tx = new TransactionId(next.incrementAndGet());
        status.put(tx, TransactionStatus.ACTIVE);
        return tx;
      }

      @Override
      public void commit(TransactionId tx) {
        status.put(tx, TransactionStatus.COMMITTED);
      }

      @Override
      public void abort(TransactionId tx) {
        status.put(tx, TransactionStatus.ABORTED);
      }

      @Override
      public TransactionStatus status(TransactionId tx) {
        return status.getOrDefault(tx, TransactionStatus.ABORTED);
      }
    };
  }
}
