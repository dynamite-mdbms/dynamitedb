package dynamitedb.qp.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import dynamitedb.contracts.query.JoinKind;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tes QP3 (QP-05, QP-06, QP-07) dengan JUnit 5. */
final class OperatorsTest {

  private static final TransactionId TX = new TransactionId(7);
  private static final Expression ANY_PREDICATE = new LiteralExpression(new BooleanValue(true));

  // ---------------------------------------------------------------- ScanOperator (QP-05)

  @Test
  @DisplayName("Scan: semua baris keluar urut, dengan alias+RID di origins")
  void scan01() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(3));
    ScanOperator scan = f.scan("mahasiswa", "m");
    scan.open();
    List<OperatorRow> rows = drain(scan);
    scan.close();
    assertEquals(3, rows.size());
    assertEquals(new IntValue(1), rows.get(0).row().values().get(0));
    assertEquals(new IntValue(3), rows.get(2).row().values().get(0));
    assertEquals(rid(0), rows.get(0).origins().get("m"));
  }

  @Test
  @DisplayName("Scan: tabel kosong menghasilkan 0 baris, bukan error")
  void scan02() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", List.of());
    ScanOperator scan = f.scan("mahasiswa", "m");
    scan.open();
    assertEquals(0, drain(scan).size());
    scan.close();
  }

  @Test
  @DisplayName("Scan: izin READ diminta SEBELUM kursor dibuka")
  void scan03() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(1));
    ScanOperator scan = f.scan("mahasiswa", "m");
    scan.open();
    assertEquals(List.of("authorize READ mahasiswa", "openTableScan mahasiswa"), f.events);
    scan.close();
  }

  @Test
  @DisplayName("Scan: kalau izin ditolak, kursor TIDAK dibuka")
  void scan04() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(1));
    f.coordinator.failWith = new DbmsException(ErrorCode.ACCESS_ABORTED, "aborted oleh CCM");
    ScanOperator scan = f.scan("mahasiswa", "m");
    expectError(ErrorCode.ACCESS_ABORTED, scan::open);
    assertTrue(f.reader.cursors.isEmpty(), "kursor tidak boleh terbuka");
  }

  @Test
  @DisplayName("Scan: dibaca bertahap (tidak memuat seluruh tabel sekaligus)")
  void scan05() {
    Fixture f = new Fixture();
    f.reader.tables.put("besar", students(10_000));
    ScanOperator scan = f.scan("besar", "b");
    scan.open();
    scan.next();
    assertEquals(1, f.reader.cursors.get(0).pulled);
    scan.close();
  }

  @Test
  @DisplayName("Scan: tabel besar (10.000 baris) tampil lengkap")
  void scan06() {
    Fixture f = new Fixture();
    f.reader.tables.put("besar", students(10_000));
    ScanOperator scan = f.scan("besar", "b");
    scan.open();
    assertEquals(10_000, drain(scan).size());
    scan.close();
  }

  @Test
  @DisplayName("Scan: close() dua kali aman dan kursor ditutup")
  void scan07() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(2));
    ScanOperator scan = f.scan("mahasiswa", "m");
    scan.open();
    scan.close();
    scan.close();
    assertEquals(1, f.reader.cursors.get(0).closed);
  }

  @Test
  @DisplayName("Scan: next() sebelum open() -> INVALID_STATE")
  void scan08() {
    Fixture f = new Fixture();
    ScanOperator scan = f.scan("mahasiswa", "m");
    expectError(ErrorCode.INVALID_STATE, scan::next);
  }

  @Test
  @DisplayName("Scan: open() dua kali tidak membocorkan kursor lama")
  void scan09() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(2));
    ScanOperator scan = f.scan("mahasiswa", "m");
    scan.open();
    scan.open();
    assertEquals(2, f.reader.cursors.size());
    assertEquals(1, f.reader.cursors.get(0).closed);
    scan.close();
  }

  // --------------------------------------------------------------- FilterOperator (QP-06)

  @Test
  @DisplayName("Filter: hanya baris yang predikatnya TRUE yang lolos")
  void filter01() {
    ListOperator child = ListOperator.of(studentRows(5));
    FilterOperator filter =
        new FilterOperator(child, ANY_PREDICATE, evaluator(r -> nim(r) % 2 == 1));
    filter.open();
    List<OperatorRow> rows = drain(filter);
    assertEquals(3, rows.size());
    assertEquals(1, nim(rows.get(0)));
    assertEquals(5, nim(rows.get(2)));
  }

  @Test
  @DisplayName("Filter: tidak ada yang lolos -> 0 baris, bukan error")
  void filter02() {
    FilterOperator filter =
        new FilterOperator(ListOperator.of(studentRows(5)), ANY_PREDICATE, evaluator(r -> false));
    filter.open();
    assertEquals(0, drain(filter).size());
  }

  @Test
  @DisplayName("Filter: ratusan ribu baris berturut-turut ditolak tanpa StackOverflow")
  void filter03() {
    FilterOperator filter =
        new FilterOperator(
            ListOperator.of(studentRows(200_000)), ANY_PREDICATE, evaluator(r -> false));
    filter.open();
    assertTrue(filter.next().isEmpty(), "harus kosong");
  }

  @Test
  @DisplayName("Filter: open/close diteruskan ke child")
  void filter04() {
    ListOperator child = ListOperator.of(studentRows(1));
    FilterOperator filter = new FilterOperator(child, ANY_PREDICATE, evaluator(r -> true));
    filter.open();
    filter.close();
    assertEquals(1, child.opened);
    assertEquals(1, child.closed);
  }

  // -------------------------------------------------------------- ProjectOperator (QP-05)

  @Test
  @DisplayName("Project: kolom sesuai urutan penulisan, bukan urutan tabel")
  void project01() {
    ProjectOperator project =
        new ProjectOperator(
            ListOperator.of(studentRows(2)), List.of(ref(null, "nama"), ref(null, "nim")));
    project.open();
    OperatorRow first = project.next().orElseThrow();
    assertEquals(new TextValue("Mhs1"), first.row().values().get(0));
    assertEquals(new IntValue(1), first.row().values().get(1));
    assertEquals("nama", first.schema().columns().get(0).name());
    assertEquals("nim", first.schema().columns().get(1).name());
  }

  @Test
  @DisplayName("Project: kolom dengan prefix tabel (m.nama)")
  void project02() {
    ProjectOperator project =
        new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref("m", "nama")));
    project.open();
    assertEquals(new TextValue("Mhs1"), project.next().orElseThrow().row().values().get(0));
  }

  @Test
  @DisplayName("Project: kolom tidak ada -> SQL_BIND")
  void project03() {
    ProjectOperator project =
        new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref(null, "alamat")));
    project.open();
    expectError(ErrorCode.SQL_BIND, project::next);
  }

  @Test
  @DisplayName("Project: prefix tabel salah -> SQL_BIND")
  void project04() {
    ProjectOperator project =
        new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref("x", "nama")));
    project.open();
    expectError(ErrorCode.SQL_BIND, project::next);
  }

  @Test
  @DisplayName("Project: kolom ambigu (dua relasi punya 'id') -> SQL_BIND")
  void project05() {
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
  }

  @Test
  @DisplayName("Project: kolom dengan prefix tapi schema tanpa relation -> SQL_BIND (bukan NPE)")
  void project06() {
    Schema noRelation = new Schema(List.of(new Column(null, "nim", DataType.INTEGER, 0, false)));
    OperatorRow row = new OperatorRow(new Row(List.of(new IntValue(1))), noRelation, Map.of());
    ProjectOperator project =
        new ProjectOperator(ListOperator.of(List.of(row)), List.of(ref("m", "nim")));
    project.open();
    expectError(ErrorCode.SQL_BIND, project::next);
  }

  @Test
  @DisplayName("Project: origins (RID) dipertahankan")
  void project07() {
    ProjectOperator project =
        new ProjectOperator(ListOperator.of(studentRows(1)), List.of(ref(null, "nim")));
    project.open();
    assertEquals(rid(0), project.next().orElseThrow().origins().get("m"));
  }

  @Test
  @DisplayName("Project: child kosong -> kosong")
  void project08() {
    ProjectOperator project =
        new ProjectOperator(ListOperator.of(List.of()), List.of(ref(null, "nim")));
    project.open();
    assertTrue(project.next().isEmpty(), "harus kosong");
  }

  @Test
  @DisplayName("Project + Filter + Scan: SELECT nama FROM mahasiswa WHERE nim>1")
  void projectfilterscan01() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(3));
    Operator root =
        new ProjectOperator(
            new FilterOperator(f.scan("mahasiswa", "m"), ANY_PREDICATE, evaluator(r -> nim(r) > 1)),
            List.of(ref("m", "nama")));
    QueryResult result =
        new ReadExecutor().execute(root, new Schema(List.of(col("m", "nama", DataType.VARCHAR))));
    assertEquals(2, result.rows().size());
    assertEquals(new TextValue("Mhs2"), result.rows().get(0).values().get(0));
  }

  // ----------------------------------------------------------------- IndexScanOperator

  @Test
  @DisplayName("IndexScan: izin READ + residual menyaring baris")
  void indexscan01() {
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
    assertEquals("authorize READ mahasiswa", f.events.get(0));
    assertEquals(2, drain(scan).size());
    scan.close();
  }

  @Test
  @DisplayName("IndexScan: next() sebelum open() -> INVALID_STATE")
  void indexscan02() {
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
  }

  // ------------------------------------------------------------------------- ReadExecutor

  @Test
  @DisplayName("Read: header tetap ada untuk tabel kosong, 0 baris")
  void read01() {
    Schema header = studentSchema("m");
    QueryResult result = new ReadExecutor().execute(ListOperator.of(List.of()), header);
    assertEquals(0, result.rows().size());
    assertEquals(header, result.schema());
  }

  @Test
  @DisplayName("Read: seluruh baris tabel besar dikembalikan, tidak dipotong")
  void read02() {
    QueryResult result =
        new ReadExecutor().execute(ListOperator.of(studentRows(10_000)), studentSchema("m"));
    assertEquals(10_000, result.rows().size());
  }

  @Test
  @DisplayName("Read: operator selalu di-close walau next() melempar exception")
  void read03() {
    ListOperator root = ListOperator.of(studentRows(3));
    root.failOnNext = 2;
    expectError(
        ErrorCode.STORAGE_FAILURE, () -> new ReadExecutor().execute(root, studentSchema("m")));
    assertEquals(1, root.closed);
  }

  // ----------------------------------------------------------------------- DeleteExecutor

  @Test
  @DisplayName("Delete: menghapus semua RID dari sumber, mengembalikan jumlah")
  void delete01() {
    DeleteFixture d = new DeleteFixture(studentRows(3, "mahasiswa"));
    assertEquals(3L, d.executor().execute(d.plan(), TX));
    assertEquals(List.of(rid(0), rid(1), rid(2)), d.store.deleted);
    assertEquals(3, d.writes.executed.size());
  }

  @Test
  @DisplayName("Delete: tabel kosong -> 0, bukan error")
  void delete02() {
    DeleteFixture d = new DeleteFixture(List.of());
    assertEquals(0L, d.executor().execute(d.plan(), TX));
  }

  @Test
  @DisplayName("Delete: izin WRITE diminta SEBELUM sumber diterjemahkan/dibaca")
  void delete03() {
    DeleteFixture d = new DeleteFixture(studentRows(1, "mahasiswa"));
    d.executor().execute(d.plan(), TX);
    assertEquals("authorize WRITE mahasiswa", d.events.get(0));
    assertEquals("translate", d.events.get(1));
  }

  @Test
  @DisplayName("Delete: izin ditolak -> tidak ada yang dihapus")
  void delete04() {
    DeleteFixture d = new DeleteFixture(studentRows(2, "mahasiswa"));
    d.coordinator.failWith = new DbmsException(ErrorCode.ACCESS_ABORTED, "aborted");
    expectError(ErrorCode.ACCESS_ABORTED, () -> d.executor().execute(d.plan(), TX));
    assertTrue(d.store.deleted.isEmpty(), "tidak boleh ada penghapusan");
  }

  @Test
  @DisplayName("Delete: operator sumber di-close walau pembacaan gagal, belum ada penghapusan")
  void delete05() {
    DeleteFixture d = new DeleteFixture(studentRows(3, "mahasiswa"));
    d.source.failOnNext = 2;
    expectError(ErrorCode.STORAGE_FAILURE, () -> d.executor().execute(d.plan(), TX));
    assertEquals(1, d.source.closed);
    assertTrue(d.store.deleted.isEmpty(), "tidak ada penghapusan sebelum pembacaan selesai");
  }

  @Test
  @DisplayName(
      "Delete: alias berbeda dari nama tabel -> menghapus atau gagal keras, bukan 0 diam-diam")
  void delete06() {
    // scan alias "m" untuk tabel "mahasiswa": origins berkunci "m", plan.table() = "mahasiswa"
    DeleteFixture d = new DeleteFixture(studentRows(2, "m"));
    long affected;
    try {
      affected = d.executor().execute(d.plan(), TX);
    } catch (DbmsException e) {
      return; // gagal keras juga diterima
    }
    assertEquals(2L, affected);
  }

  @Test
  @DisplayName("Delete: penulisan gagal di tengah -> exception diteruskan, sisanya dihentikan")
  void delete07() {
    DeleteFixture d = new DeleteFixture(studentRows(3, "mahasiswa"));
    d.writes.failOnExecute = 2;
    expectError(ErrorCode.WAL_FAILURE, () -> d.executor().execute(d.plan(), TX));
    assertEquals(2, d.store.deleted.size()); // rid ke-3 tidak sempat disiapkan
  }

  // ---------------------------------------------------------------- OperatorRows.qualify

  @Test
  @DisplayName("Qualify: relation kolom diganti dengan alias, kolom lain tidak berubah")
  void qualify01() {
    Schema table =
        new Schema(
            List.of(
                new Column("mahasiswa", "id", DataType.INTEGER, 0, false),
                new Column("mahasiswa", "nama", DataType.VARCHAR, 50, true)));
    Schema q = OperatorRows.qualify(table, "m");
    assertEquals(2, q.columns().size());
    assertEquals("m", q.columns().get(0).relation());
    assertEquals("m", q.columns().get(1).relation());
    assertEquals("nama", q.columns().get(1).name());
    assertEquals(DataType.VARCHAR, q.columns().get(1).dataType());
    assertEquals(50, q.columns().get(1).maxLength());
    assertEquals(true, q.columns().get(1).nullable());
  }

  @Test
  @DisplayName("Qualify: schema asli tidak dimutasi (immutable)")
  void qualify02() {
    Schema table = new Schema(List.of(new Column("t", "x", DataType.INTEGER, 0, false)));
    OperatorRows.qualify(table, "a");
    assertEquals("t", table.columns().get(0).relation());
  }

  @Test
  @DisplayName("Qualify: alias sama dengan nama tabel -> schema tidak berubah")
  void qualify03() {
    Schema table = studentSchema("mahasiswa");
    assertEquals(table, OperatorRows.qualify(table, "mahasiswa"));
  }

  @Test
  @DisplayName("Qualify: relation null diganti alias")
  void qualify04() {
    Schema table = new Schema(List.of(new Column(null, "nim", DataType.INTEGER, 0, false)));
    assertEquals("s", OperatorRows.qualify(table, "s").columns().get(0).relation());
  }

  @Test
  @DisplayName("Scan: kolom ber-prefix alias ditemukan, prefix nama tabel asli ditolak (SQL_BIND)")
  void scan10() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(1));
    ScanOperator scan = f.scan("mahasiswa", "m");
    scan.open();
    OperatorRow row = scan.next().orElseThrow();
    scan.close();
    row.findColumnIndex(new ColumnRef(Optional.of("m"), "nim"));
    expectError(
        ErrorCode.SQL_BIND,
        () -> row.findColumnIndex(new ColumnRef(Optional.of("mahasiswa"), "nim")));
  }

  @Test
  @DisplayName("Alias: ProjectOperator di atas scan beralias memakai s.kolom")
  void alias01() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(2));
    ProjectOperator project =
        new ProjectOperator(f.scan("mahasiswa", "s"), List.of(ref("s", "nama")));
    project.open();
    List<OperatorRow> rows = drain(project);
    project.close();
    assertEquals(2, rows.size());
    assertEquals(new TextValue("Mhs1"), rows.get(0).row().values().get(0));
  }

  @Test
  @DisplayName("Alias: FilterOperator di atas scan beralias memakai s.kolom")
  void alias02() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(3));
    FilterOperator filter =
        new FilterOperator(
            f.scan("mahasiswa", "s"),
            ANY_PREDICATE,
            evaluator(r -> ((IntValue) r.getValue(ref("s", "nim"))).value() % 2 == 1));
    filter.open();
    List<OperatorRow> rows = drain(filter);
    filter.close();
    assertEquals(2, rows.size());
  }

  @Test
  @DisplayName("Alias: self-join a dan b membedakan a.nim dan b.nim serta origins")
  void alias03() {
    Fixture f = new Fixture();
    f.reader.tables.put("mahasiswa", students(2));
    BlockNestedLoopJoinOperator join =
        new BlockNestedLoopJoinOperator(
            f.scan("mahasiswa", "a"),
            f.scan("mahasiswa", "b"),
            JoinKind.CARTESIAN,
            Optional.empty(),
            evaluator(r -> true));
    join.open();
    List<OperatorRow> rows = drain(join);
    join.close();
    assertEquals(4, rows.size());
    OperatorRow first = rows.get(0);
    assertEquals(0, first.findColumnIndex(ref("a", "nim")));
    assertEquals(2, first.findColumnIndex(ref("b", "nim")));
    assertTrue(first.origins().containsKey("a") && first.origins().containsKey("b"), "origins");
    expectError(ErrorCode.SQL_BIND, () -> first.findColumnIndex(ref(null, "nim")));
  }

  // ------------------------------------------------------------------------ test helpers

  private static void expectError(ErrorCode code, Runnable action) {
    DbmsException e = assertThrows(DbmsException.class, action::run);
    assertEquals(code, e.getCode());
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
}
