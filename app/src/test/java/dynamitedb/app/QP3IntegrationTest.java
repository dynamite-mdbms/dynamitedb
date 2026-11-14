package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.catalog.SampleDatabaseLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tes integrasi QP3 (Scan, Filter, Project, DELETE, OperatorRows.qualify) melalui QueryProcessor
 * dengan QO, SM, CCM, dan FRM dari StubRuntime serta basis data contoh (student, course,
 * enrollment).
 */
class QP3IntegrationTest {
  private static final TransactionId READ_TX = new TransactionId(1);
  private static final int GPA = 2; // indeks kolom gpa pada tabel student

  @TempDir Path directory;

  private StubRuntime runtime;
  private SessionId session;

  @BeforeEach
  void start() throws Exception {
    DbmsConfig config =
        new DbmsConfig(0, directory.resolve("data"), DbmsConfig.defaults().protocol());
    runtime = new StubRuntime(config);
    runtime.bootstrap().start(config);
    new SampleDatabaseLoader(runtime.storage(), runtime.concurrency(), runtime.concurrency())
        .loadIfAbsent(runtime.catalog());
    session = runtime.processor().openSession("qp3-integration");
  }

  @AfterEach
  void stop() {
    try {
      runtime.processor().closeSession(session);
    } finally {
      runtime.bootstrap().shutdown();
    }
  }

  @Test
  @DisplayName("SELECT * menampilkan seluruh baris tabel dengan header lengkap")
  void selectAll() {
    QueryResult result = sql("SELECT * FROM student");
    assertEquals(storedRows("student").size(), result.rows().size());
    assertEquals(4, result.schema().columns().size());
    assertTrue(result.rows().size() >= 50, "tabel contoh harus berisi minimal 50 baris");
  }

  @Test
  @DisplayName("Alias: SELECT s.name FROM student AS s WHERE s.gpa > 3.0 (menguji qualify)")
  void aliasedProjectionAndFilter() {
    long expected = storedRows("student").stream().filter(r -> gpaAbove(r, 3.0)).count();

    QueryResult result = sql("SELECT s.name FROM student AS s WHERE s.gpa > 3.0");

    assertEquals(expected, result.rows().size());
    assertEquals(1, result.schema().columns().size());
    assertEquals("name", result.schema().columns().get(0).name());
  }

  @Test
  @DisplayName("WHERE gpa > 3.0: baris dengan gpa NULL tidak ikut")
  void nullGpaIsExcluded() {
    QueryResult result = sql("SELECT name, gpa FROM student WHERE gpa > 3.0");
    for (Row row : result.rows()) {
      assertFalse(row.values().get(1) instanceof NullValue, "gpa NULL tidak boleh lolos filter");
    }
  }

  @Test
  @DisplayName("Hasil kosong tetap menampilkan header dengan 0 baris")
  void emptyResultKeepsHeader() {
    QueryResult result = sql("SELECT * FROM student WHERE gpa > 100");
    assertEquals(0, result.rows().size());
    assertEquals(4, result.schema().columns().size());
  }

  @Test
  @DisplayName("Self-join student AS a JOIN student AS b membedakan a.student_id dan b.student_id")
  void selfJoinWithAliases() {
    Map<Integer, Integer> frequency = new HashMap<>();
    for (Row row : storedRows("student")) {
      frequency.merge(((IntValue) row.values().get(0)).value(), 1, Integer::sum);
    }
    long expected = frequency.values().stream().mapToLong(n -> (long) n * n).sum();

    QueryResult result =
        sql(
            "SELECT a.student_id FROM student AS a JOIN student AS b"
                + " ON a.student_id = b.student_id");

    assertEquals(expected, result.rows().size());
  }

  @Test
  @DisplayName("DELETE ... WHERE menghapus baris yang sesuai, SELECT sesudahnya konsisten")
  void deleteThenSelect() {
    int total = storedRows("student").size();
    long toDelete = storedRows("student").stream().filter(r -> gpaBelow(r, 2.0)).count();

    QueryResult deleted = sql("DELETE FROM student WHERE gpa < 2.0");

    assertEquals(toDelete, deleted.affectedRows());
    assertEquals(total - toDelete, sql("SELECT * FROM student").rows().size());
  }

  @Test
  @DisplayName("BEGIN; DELETE; ABORT: perintah ABORT sampai ke rollback FRM")
  void abortReachesRollbackService() {
    sql("BEGIN");
    sql("DELETE FROM student");
    sql("ABORT");

    assertTrue(
        runtime.recoveryContext().calls().contains("UndoManager.rollbackCompletely"),
        "ABORT harus memanggil RollbackService melalui CCM");
  }

  @Test
  @Disabled(
      "StubRuntime intentionally uses a mock UndoManager; real rollback is covered by"
          + " PersistentRuntimeTest")
  @DisplayName("BEGIN; DELETE; ABORT; data kembali (membutuhkan rollback FRM yang nyata)")
  void abortRestoresDeletedRows() {
    int total = storedRows("student").size();

    sql("BEGIN");
    sql("DELETE FROM student");
    sql("ABORT");

    assertEquals(total, sql("SELECT * FROM student").rows().size());
  }

  // ------------------------------------------------------------------------------ helpers

  private QueryResult sql(String statement) {
    return runtime.processor().execute(session, statement);
  }

  /** Membaca langsung dari SM sebagai pembanding hasil query. */
  private List<Row> storedRows(String table) {
    List<Row> rows = new ArrayList<>();
    try (RowCursor cursor = runtime.storage().openTableScan(table, READ_TX)) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.get().row());
      }
    }
    return rows;
  }

  private static boolean gpaAbove(Row row, double limit) {
    return row.values().get(GPA) instanceof FloatValue f && f.value() > limit;
  }

  private static boolean gpaBelow(Row row, double limit) {
    return row.values().get(GPA) instanceof FloatValue f && f.value() < limit;
  }
}
