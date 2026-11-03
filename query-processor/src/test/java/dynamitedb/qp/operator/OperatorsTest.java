package dynamitedb.qp.operator;

import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.execution.DeleteExecutor;
import dynamitedb.qp.execution.PlanTranslator;
import dynamitedb.qp.execution.ReadExecutor;
import dynamitedb.qp.execution.WriteCoordinator;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Tes QP3 (QP-05, QP-06, QP-07) tanpa framework pihak ketiga. Jalankan lewat main(); exit code 1
 * kalau ada tes yang gagal.
 */
public final class OperatorsTest {

  private static final TransactionId TX = new TransactionId(7);
  private static final Expression ANY_PREDICATE = new LiteralExpression(new BooleanValue(true));
  private static int passed = 0;
  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) {
    scanTests();
    filterTests();
    projectTests();
    indexScanTests();
    readExecutorTests();
    deleteExecutorTests();

    System.out.println(passed + " passed, " + failures.size() + " failed");
    failures.forEach(f -> System.out.println("FAILED: " + f));
    if (!failures.isEmpty()) {
      System.exit(1);
    }
  }

  // ---------------------------------------------------------------- ScanOperator (QP-05)

  private static void scanTests() {
    test(
        "Scan: semua baris keluar urut, dengan alias+RID di origins",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("mahasiswa", students(3));
          ScanOperator scan = f.scan("mahasiswa", "m");
          scan.open();
          List<OperatorRow> rows = drain(scan);
          scan.close();
          eq(3, rows.size());
          eq(new IntValue(1), rows.get(0).row().values().get(0));
          eq(new IntValue(3), rows.get(2).row().values().get(0));
          eq(rid(0), rows.get(0).origins().get("m"));
        });

    test(
        "Scan: tabel kosong menghasilkan 0 baris, bukan error",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("mahasiswa", List.of());
          ScanOperator scan = f.scan("mahasiswa", "m");
          scan.open();
          eq(0, drain(scan).size());
          scan.close();
        });

    test(
        "Scan: izin READ diminta SEBELUM kursor dibuka",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("mahasiswa", students(1));
          ScanOperator scan = f.scan("mahasiswa", "m");
          scan.open();
          eq(List.of("authorize READ mahasiswa", "openTableScan mahasiswa"), f.events);
          scan.close();
        });

    test(
        "Scan: kalau izin ditolak, kursor TIDAK dibuka",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("mahasiswa", students(1));
          f.coordinator.failWith = new DbmsException(ErrorCode.ACCESS_ABORTED, "aborted oleh CCM");
          ScanOperator scan = f.scan("mahasiswa", "m");
          expectError(ErrorCode.ACCESS_ABORTED, scan::open);
          check(f.reader.cursors.isEmpty(), "kursor tidak boleh terbuka");
        });

    test(
        "Scan: dibaca bertahap (tidak memuat seluruh tabel sekaligus)",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("besar", students(10_000));
          ScanOperator scan = f.scan("besar", "b");
          scan.open();
          scan.next();
          eq(1, f.reader.cursors.get(0).pulled);
          scan.close();
        });

    test(
        "Scan: tabel besar (10.000 baris) tampil lengkap",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("besar", students(10_000));
          ScanOperator scan = f.scan("besar", "b");
          scan.open();
          eq(10_000, drain(scan).size());
          scan.close();
        });

    test(
        "Scan: close() dua kali aman dan kursor ditutup",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("mahasiswa", students(2));
          ScanOperator scan = f.scan("mahasiswa", "m");
          scan.open();
          scan.close();
          scan.close();
          eq(1, f.reader.cursors.get(0).closed);
        });

    test(
        "Scan: next() sebelum open() -> INVALID_STATE",
        () -> {
          Fixture f = new Fixture();
          ScanOperator scan = f.scan("mahasiswa", "m");
          expectError(ErrorCode.INVALID_STATE, scan::next);
        });

    test(
        "Scan: open() dua kali tidak membocorkan kursor lama",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("mahasiswa", students(2));
          ScanOperator scan = f.scan("mahasiswa", "m");
          scan.open();
          scan.open();
          eq(2, f.reader.cursors.size());
          eq(1, f.reader.cursors.get(0).closed);
          scan.close();
        });
  }

  // --------------------------------------------------------------- FilterOperator (QP-06)

  private static void filterTests() {
    test(
        "Filter: hanya baris yang predikatnya TRUE yang lolos",
        () -> {
          ListOperator child = ListOperator.of(studentRows(5));
          FilterOperator filter =
              new FilterOperator(child, ANY_PREDICATE, evaluator(r -> nim(r) % 2 == 1));
          filter.open();
          List<OperatorRow> rows = drain(filter);
          eq(3, rows.size());
          eq(1, nim(rows.get(0)));
          eq(5, nim(rows.get(2)));
        });

    test(
        "Filter: tidak ada yang lolos -> 0 baris, bukan error",
        () -> {
          FilterOperator filter =
              new FilterOperator(
                  ListOperator.of(studentRows(5)), ANY_PREDICATE, evaluator(r -> false));
          filter.open();
          eq(0, drain(filter).size());
        });

    test(
        "Filter: ratusan ribu baris berturut-turut ditolak tanpa StackOverflow",
        () -> {
          FilterOperator filter =
              new FilterOperator(
                  ListOperator.of(studentRows(200_000)), ANY_PREDICATE, evaluator(r -> false));
          filter.open();
          check(filter.next().isEmpty(), "harus kosong");
        });

    test(
        "Filter: open/close diteruskan ke child",
        () -> {
          ListOperator child = ListOperator.of(studentRows(1));
          FilterOperator filter = new FilterOperator(child, ANY_PREDICATE, evaluator(r -> true));
          filter.open();
          filter.close();
          eq(1, child.opened);
          eq(1, child.closed);
        });
  }

  // -------------------------------------------------------------- ProjectOperator (QP-05)

  private static void projectTests() {
    test(
        "Project: kolom sesuai urutan penulisan, bukan urutan tabel",
        () -> {
          ProjectOperator project =
              new ProjectOperator(
                  ListOperator.of(studentRows(2)), List.of(ref(null, "nama"), ref(null, "nim")));
          project.open();
          OperatorRow first = project.next().orElseThrow();
          eq(new TextValue("Mhs1"), first.row().values().get(0));
          eq(new IntValue(1), first.row().values().get(1));
          eq("nama", first.schema().columns().get(0).name());
          eq("nim", first.schema().columns().get(1).name());
        });

    test(
        "Project: kolom dengan prefix tabel (m.nama)",
        () -> {
          ProjectOperator project =
              new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref("m", "nama")));
          project.open();
          eq(new TextValue("Mhs1"), project.next().orElseThrow().row().values().get(0));
        });

    test(
        "Project: kolom tidak ada -> SQL_BIND",
        () -> {
          ProjectOperator project =
              new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref(null, "alamat")));
          project.open();
          expectError(ErrorCode.SQL_BIND, project::next);
        });

    test(
        "Project: prefix tabel salah -> SQL_BIND",
        () -> {
          ProjectOperator project =
              new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref("x", "nama")));
          project.open();
          expectError(ErrorCode.SQL_BIND, project::next);
        });

    test(
        "Project: kolom ambigu (dua relasi punya 'id') -> SQL_BIND",
        () -> {
          Schema joined =
              new Schema(
                  List.of(
                      new Column("a", "id", DataType.INTEGER, 0, false),
                      new Column("b", "id", DataType.INTEGER, 0, false)));
          OperatorRow row =
              new OperatorRow(new Row(List.of(new IntValue(1), new IntValue(2))), joined, Map.of());
          ProjectOperator project =
              new ProjectOperator(ListOperator.of(List.of(row)), List.of(ref(null, "id")));
          project.open();
          expectError(ErrorCode.SQL_BIND, project::next);
        });

    test(
        "Project: kolom dengan prefix tapi schema tanpa relation -> SQL_BIND (bukan NPE)",
        () -> {
          Schema noRelation =
              new Schema(List.of(new Column(null, "nim", DataType.INTEGER, 0, false)));
          OperatorRow row =
              new OperatorRow(new Row(List.of(new IntValue(1))), noRelation, Map.of());
          ProjectOperator project =
              new ProjectOperator(ListOperator.of(List.of(row)), List.of(ref("m", "nim")));
          project.open();
          expectError(ErrorCode.SQL_BIND, project::next);
        });

    test(
        "Project: origins (RID) dipertahankan",
        () -> {
          ProjectOperator project =
              new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref(null, "nim")));
          project.open();
          eq(rid(0), project.next().orElseThrow().origins().get("m"));
        });

    test(
        "Project: child kosong -> kosong",
        () -> {
          ProjectOperator project =
              new ProjectOperator(ListOperator.of(List.of()), List.of(ref(null, "nim")));
          project.open();
          check(project.next().isEmpty(), "harus kosong");
        });

    test(
        "Project + Filter + Scan: SELECT nama FROM mahasiswa WHERE nim>1",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("mahasiswa", students(3));
          Operator root =
              new ProjectOperator(
                  new FilterOperator(
                      f.scan("mahasiswa", "m"), ANY_PREDICATE, evaluator(r -> nim(r) > 1)),
                  List.of(ref("m", "nama")));
          QueryResult result =
              new ReadExecutor()
                  .execute(root, new Schema(List.of(col("m", "nama", DataType.VARCHAR))));
          eq(2, result.rows().size());
          eq(new TextValue("Mhs2"), result.rows().get(0).values().get(0));
        });
  }

  // ----------------------------------------------------------------- IndexScanOperator

  private static void indexScanTests() {
    test(
        "IndexScan: izin READ + residual menyaring baris",
        () -> {
          Fixture f = new Fixture();
          f.reader.tables.put("idx_nim", students(4));
          IndexScanOperator scan =
              new IndexScanOperator(
                  f.reader,
                  "idx_nim",
                  new IndexPredicate(ComparisonOp.GTE, new IntValue(1)),
                  "mahasiswa",
                  "m",
                  studentSchema("m"),
                  TX,
                  f.coordinator,
                  Optional.of(ANY_PREDICATE),
                  evaluator(r -> nim(r) % 2 == 0));
          scan.open();
          eq("authorize READ mahasiswa", f.events.get(0));
          eq(2, drain(scan).size());
          scan.close();
        });

    test(
        "IndexScan: next() sebelum open() -> INVALID_STATE",
        () -> {
          Fixture f = new Fixture();
          IndexScanOperator scan =
              new IndexScanOperator(
                  f.reader,
                  "idx",
                  new IndexPredicate(ComparisonOp.EQ, new IntValue(1)),
                  "mahasiswa",
                  "m",
                  studentSchema("m"),
                  TX,
                  f.coordinator,
                  Optional.empty(),
                  evaluator(r -> true));
          expectError(ErrorCode.INVALID_STATE, scan::next);
        });
  }

  // ------------------------------------------------------------------------- ReadExecutor

  private static void readExecutorTests() {
    test(
        "Read: header tetap ada untuk tabel kosong, 0 baris",
        () -> {
          Schema header = studentSchema("m");
          QueryResult result = new ReadExecutor().execute(ListOperator.of(List.of()), header);
          eq(0, result.rows().size());
          eq(header, result.schema());
        });

    test(
        "Read: seluruh baris tabel besar dikembalikan, tidak dipotong",
        () -> {
          QueryResult result =
              new ReadExecutor().execute(ListOperator.of(studentRows(10_000)), studentSchema("m"));
          eq(10_000, result.rows().size());
        });

    test(
        "Read: operator selalu di-close walau next() melempar exception",
        () -> {
          ListOperator root = ListOperator.of(studentRows(3));
          root.failOnNext = 2;
          expectError(
              ErrorCode.STORAGE_FAILURE,
              () -> new ReadExecutor().execute(root, studentSchema("m")));
          eq(1, root.closed);
        });
  }

  // ----------------------------------------------------------------------- DeleteExecutor

  private static void deleteExecutorTests() {
    test(
        "Delete: menghapus semua RID dari sumber, mengembalikan jumlah",
        () -> {
          DeleteFixture d = new DeleteFixture(studentRows(3, "mahasiswa"));
          eq(3L, d.executor().execute(d.plan(), TX));
          eq(List.of(rid(0), rid(1), rid(2)), d.store.deleted);
          eq(3, d.writes.executed.size());
        });

    test(
        "Delete: tabel kosong -> 0, bukan error",
        () -> {
          DeleteFixture d = new DeleteFixture(List.of());
          eq(0L, d.executor().execute(d.plan(), TX));
        });

    test(
        "Delete: izin WRITE diminta SEBELUM sumber diterjemahkan/dibaca",
        () -> {
          DeleteFixture d = new DeleteFixture(studentRows(1, "mahasiswa"));
          d.executor().execute(d.plan(), TX);
          eq("authorize WRITE mahasiswa", d.events.get(0));
          eq("translate", d.events.get(1));
        });

    test(
        "Delete: izin ditolak -> tidak ada yang dihapus",
        () -> {
          DeleteFixture d = new DeleteFixture(studentRows(2, "mahasiswa"));
          d.coordinator.failWith = new DbmsException(ErrorCode.ACCESS_ABORTED, "aborted");
          expectError(ErrorCode.ACCESS_ABORTED, () -> d.executor().execute(d.plan(), TX));
          check(d.store.deleted.isEmpty(), "tidak boleh ada penghapusan");
        });

    test(
        "Delete: operator sumber di-close walau pembacaan gagal, belum ada penghapusan",
        () -> {
          DeleteFixture d = new DeleteFixture(studentRows(3, "mahasiswa"));
          d.source.failOnNext = 2;
          expectError(ErrorCode.STORAGE_FAILURE, () -> d.executor().execute(d.plan(), TX));
          eq(1, d.source.closed);
          check(d.store.deleted.isEmpty(), "tidak ada penghapusan sebelum pembacaan selesai");
        });

    test(
        "Delete: alias berbeda dari nama tabel -> tetap menghapus atau gagal keras, bukan diam-diam"
            + " 0",
        () -> {
          // scan alias "m" untuk tabel "mahasiswa": origins berkunci "m", plan.table() =
          // "mahasiswa"
          DeleteFixture d = new DeleteFixture(studentRows(2, "m"));
          long affected;
          try {
            affected = d.executor().execute(d.plan(), TX);
          } catch (DbmsException e) {
            return; // gagal keras juga diterima
          }
          eq(2L, affected);
        });

    test(
        "Delete: penulisan gagal di tengah -> exception diteruskan, penghapusan berikutnya"
            + " dihentikan",
        () -> {
          DeleteFixture d = new DeleteFixture(studentRows(3, "mahasiswa"));
          d.writes.failOnExecute = 2;
          expectError(ErrorCode.WAL_FAILURE, () -> d.executor().execute(d.plan(), TX));
          eq(2, d.store.deleted.size()); // rid ke-3 tidak sempat disiapkan
        });
  }

  // ------------------------------------------------------------------------ test helpers

  private static void test(String name, Runnable body) {
    try {
      body.run();
      passed++;
      System.out.println("PASS  " + name);
    } catch (Throwable t) {
      failures.add(name + " -> " + t);
      System.out.println("FAIL  " + name + " -> " + t);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static void eq(Object expected, Object actual) {
    if (!java.util.Objects.equals(expected, actual)) {
      throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
  }

  private static void expectError(ErrorCode code, Runnable action) {
    try {
      action.run();
    } catch (DbmsException e) {
      eq(code, e.getCode());
      return;
    }
    throw new AssertionError("expected DbmsException " + code + " tapi tidak ada exception");
  }

  private static List<OperatorRow> drain(Operator op) {
    List<OperatorRow> rows = new ArrayList<>();
    for (Optional<OperatorRow> r = op.next(); r.isPresent(); r = op.next()) {
      rows.add(r.get());
    }
    return rows;
  }

  private static RecordId rid(int i) {
    return new RecordId(1, i / 100, i % 100, 0);
  }

  private static Column col(String relation, String name, DataType type) {
    return new Column(relation, name, type, 255, true);
  }

  private static Schema studentSchema(String alias) {
    return new Schema(
        List.of(col(alias, "nim", DataType.INTEGER), col(alias, "nama", DataType.VARCHAR)));
  }

  private static List<StoredRow> students(int n) {
    List<StoredRow> rows = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      rows.add(
          new StoredRow(
              rid(i), new Row(List.of(new IntValue(i + 1), new TextValue("Mhs" + (i + 1))))));
    }
    return rows;
  }

  private static List<OperatorRow> studentRows(int n) {
    return studentRows(n, "m");
  }

  private static List<OperatorRow> studentRows(int n, String alias) {
    List<OperatorRow> rows = new ArrayList<>(n);
    Schema schema = studentSchema(alias);
    for (StoredRow s : students(n)) {
      rows.add(new OperatorRow(s.row(), schema, Map.of(alias, s.rid())));
    }
    return rows;
  }

  private static int nim(OperatorRow row) {
    return ((IntValue) row.row().values().get(0)).value();
  }

  private static ColumnRef ref(String relation, String column) {
    return new ColumnRef(Optional.ofNullable(relation), column);
  }

  /**
   * Objek pengganti untuk dependensi interface milik komponen lain. Konstruktor kelas QP lain
   * (TransactionCoordinator, WriteCoordinator, PlanTranslator) menolak null, jadi tidak boleh
   * dioper null. Setiap pemanggilan metodenya melempar exception supaya pemakaian tak sengaja
   * langsung ketahuan.
   */
  @SuppressWarnings("unchecked")
  private static <T> T stub(Class<T> type) {
    return (T)
        java.lang.reflect.Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "toString":
                  return "stub(" + type.getSimpleName() + ")";
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                default:
                  throw new UnsupportedOperationException(
                      type.getSimpleName() + "." + method.getName() + " tidak boleh dipanggil");
              }
            });
  }

  private static ExpressionEvaluator evaluator(Predicate<OperatorRow> rule) {
    return new ExpressionEvaluator() {
      @Override
      public boolean evaluatePredicate(Expression expr, OperatorRow row) {
        return rule.test(row);
      }
    };
  }

  // ------------------------------------------------------------------------------- fakes

  /** Fake SM + CCM yang mencatat urutan panggilan ke satu daftar events. */
  private static final class Fixture {
    final List<String> events = new ArrayList<>();
    final FakeReader reader = new FakeReader(events);
    final FakeCoordinator coordinator = new FakeCoordinator(events);

    ScanOperator scan(String table, String alias) {
      return new ScanOperator(reader, table, alias, studentSchema(alias), TX, coordinator);
    }
  }

  private static final class FakeCursor implements RowCursor {
    private final List<StoredRow> rows;
    int pulled = 0;
    int closed = 0;

    FakeCursor(List<StoredRow> rows) {
      this.rows = rows;
    }

    @Override
    public Optional<StoredRow> next() {
      return pulled < rows.size() ? Optional.of(rows.get(pulled++)) : Optional.empty();
    }

    @Override
    public void close() {
      closed++;
    }
  }

  private static final class FakeReader implements TupleReader {
    final Map<String, List<StoredRow>> tables = new HashMap<>();
    final List<FakeCursor> cursors = new ArrayList<>();
    private final List<String> events;

    FakeReader(List<String> events) {
      this.events = events;
    }

    @Override
    public RowCursor openTableScan(String table, TransactionId tx) {
      events.add("openTableScan " + table);
      return open(table);
    }

    @Override
    public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
      events.add("openIndexScan " + index);
      return open(index);
    }

    @Override
    public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
      return Optional.empty();
    }

    private RowCursor open(String name) {
      FakeCursor cursor = new FakeCursor(tables.getOrDefault(name, List.of()));
      cursors.add(cursor);
      return cursor;
    }
  }

  private static final class FakeCoordinator extends TransactionCoordinator {
    private final List<String> events;
    DbmsException failWith;

    FakeCoordinator(List<String> events) {
      super(
          stub(TransactionManager.class), stub(AccessController.class), stub(CatalogAccess.class));
      this.events = events;
    }

    @Override
    public void authorizeTable(TransactionId tx, String table, AccessMode mode) {
      events.add("authorize " + mode + " " + table);
      if (failWith != null) {
        throw failWith;
      }
    }
  }

  /** Operator hasil jadi-jadian dengan penghitung open/close dan kegagalan terprogram. */
  private static final class ListOperator implements Operator {
    private final List<OperatorRow> rows;
    private int index = 0;
    int opened = 0;
    int closed = 0;
    int failOnNext = -1; // next() ke-N (1-based) melempar STORAGE_FAILURE
    private int calls = 0;

    private ListOperator(List<OperatorRow> rows) {
      this.rows = rows;
    }

    static ListOperator of(List<OperatorRow> rows) {
      return new ListOperator(rows);
    }

    @Override
    public void open() {
      opened++;
      index = 0;
    }

    @Override
    public Optional<OperatorRow> next() {
      if (++calls == failOnNext) {
        throw new DbmsException(ErrorCode.STORAGE_FAILURE, "gagal baca (fake)");
      }
      return index < rows.size() ? Optional.of(rows.get(index++)) : Optional.empty();
    }

    @Override
    public void close() {
      closed++;
    }
  }

  private static final class FakeWriteStore implements WriteStore {
    final List<RecordId> deleted = new ArrayList<>();

    @Override
    public PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
      deleted.add(rid);
      RecordImage image = new RecordImage(true, ImmutableBytes.copyOf(new byte[0]));
      return new PreparedWrite(
          tx, "mahasiswa", rid, image, image, new WriteReservationId(UUID.randomUUID()));
    }

    @Override
    public void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn) {}

    @Override
    public void cancelPrepared(PreparedWrite prepared) {}
  }

  private static final class FakeWriteCoordinator extends WriteCoordinator {
    final List<PreparedWrite> executed = new ArrayList<>();
    final List<PreparedWrite> cancelled = new ArrayList<>();
    int failOnExecute = -1; // execute() ke-N (1-based) melempar WAL_FAILURE
    private int calls = 0;

    FakeWriteCoordinator() {
      super(stub(WriteStore.class), stub(ChangeLogger.class));
    }

    @Override
    public void execute(PreparedWrite prepared) {
      if (++calls == failOnExecute) {
        throw new DbmsException(ErrorCode.WAL_FAILURE, "log gagal (fake)");
      }
      executed.add(prepared);
    }

    @Override
    public void cancel(PreparedWrite prepared) {
      cancelled.add(prepared);
    }
  }

  private static final class FakeTranslator extends PlanTranslator {
    private final Operator source;
    private final List<String> events;

    FakeTranslator(Operator source, List<String> events) {
      super(
          new ExpressionEvaluator(),
          stub(TupleReader.class),
          stub(CatalogAccess.class),
          new FakeCoordinator(new ArrayList<>()));
      this.source = source;
      this.events = events;
    }

    @Override
    public Operator translate(PlanNode plan, TransactionId tx) {
      events.add("translate");
      return source;
    }
  }

  /** DELETE FROM mahasiswa (alias m) dengan sumber ListOperator. */
  private static final class DeleteFixture {
    final List<String> events = new ArrayList<>();
    final FakeCoordinator coordinator = new FakeCoordinator(events);
    final FakeWriteStore store = new FakeWriteStore();
    final FakeWriteCoordinator writes = new FakeWriteCoordinator();
    final ListOperator source;

    DeleteFixture(List<OperatorRow> sourceRows) {
      this.source = ListOperator.of(sourceRows);
    }

    DeleteExecutor executor() {
      return new DeleteExecutor(
          store,
          writes,
          new FakeTranslator(source, events),
          new ExpressionEvaluator(),
          coordinator);
    }

    DeletePlan plan() {
      return new DeletePlan(new TableScanPlan("mahasiswa", "m"), "mahasiswa");
    }
  }

  private OperatorsTest() {}
}
