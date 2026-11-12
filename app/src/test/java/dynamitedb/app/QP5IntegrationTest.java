package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.catalog.SampleDatabaseLoader;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tes integrasi QP5 (INSERT, UPDATE, CREATE INDEX, dan antrean lock) melalui QueryProcessor dengan
 * QO, SM, dan CCM nyata dari StubRuntime.
 */
class QP5IntegrationTest {
  private static final TransactionId READ_TX = new TransactionId(1);
  private static final int GPA = 2; // indeks kolom gpa (FLOAT) pada tabel student

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
    session = runtime.processor().openSession("qp5-integration");
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
  @DisplayName("INSERT bilangan bulat ke kolom FLOAT disimpan sebagai FLOAT (issue #51)")
  void insertPromotesIntegerToFloat() {
    QueryResult result = sql("INSERT INTO student VALUES (9002, 'Int GPA', 3, 'A')");

    assertEquals(1, result.affectedRows());
    assertEquals(new FloatValue(3.0), storedGpa(9002));
    assertEquals(
        new FloatValue(3.0),
        sql("SELECT gpa FROM student WHERE student_id = 9002").rows().get(0).values().get(0));
  }

  @Test
  @DisplayName("INSERT dalam BEGIN lalu COMMIT juga menaikkan bilangan bulat ke FLOAT")
  void insertInsideTransactionPromotesIntegerToFloat() {
    sql("BEGIN");
    sql("INSERT INTO student VALUES (9003, 'Tx GPA', 4, 'A')");
    sql("COMMIT");

    assertEquals(new FloatValue(4.0), storedGpa(9003));
  }

  @Test
  @DisplayName("UPDATE dengan bilangan bulat ke kolom FLOAT tetap konsisten dengan INSERT")
  void updatePromotesIntegerToFloat() {
    sql("INSERT INTO student VALUES (9004, 'Upd GPA', 2.5, 'B')");

    QueryResult result = sql("UPDATE student SET gpa = 3 WHERE student_id = 9004");

    assertEquals(1, result.affectedRows());
    assertEquals(new FloatValue(3.0), storedGpa(9004));
  }

  @Test
  @DisplayName("CREATE INDEX di luar BEGIN terdaftar di katalog SM, di dalam BEGIN ditolak")
  void createIndexOutsideTransactionOnly() {
    QueryResult result = sql("CREATE INDEX idx_student_gpa ON student(gpa) USING B_PLUS_TREE");

    assertEquals("Index idx_student_gpa created", result.message());
    assertTrue(
        runtime.catalog().listIndexes("student").stream()
            .anyMatch(
                index ->
                    index.name().equals("idx_student_gpa")
                        && index.kind() == IndexKind.B_PLUS_TREE));

    sql("BEGIN");
    DbmsException error =
        assertThrows(DbmsException.class, () -> sql("CREATE INDEX idx_x ON student(name)"));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertEquals("COMMIT", sql("COMMIT").message());
  }

  @Test
  @DisplayName("Sesi kedua menunggu lock WRITE (WAITING) dan lanjut setelah sesi pertama COMMIT")
  void secondWriterWaitsForCommit() throws Exception {
    SessionId other = runtime.processor().openSession("qp5-integration-2");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      sql("BEGIN");
      sql("INSERT INTO student VALUES (9005, 'Lock A', 3.5, 'A')");

      Future<QueryResult> waiting =
          pool.submit(
              () ->
                  runtime
                      .processor()
                      .execute(other, "INSERT INTO student VALUES (9006, 'Lock B', 2, 'B')"));
      Thread.sleep(300);
      assertFalse(waiting.isDone(), "INSERT sesi kedua harus menunggu lock milik sesi pertama");

      sql("COMMIT");

      assertEquals(1, waiting.get(10, TimeUnit.SECONDS).affectedRows());
      assertEquals(new FloatValue(3.5), storedGpa(9005));
      assertEquals(new FloatValue(2.0), storedGpa(9006));
    } finally {
      pool.shutdownNow();
      runtime.processor().closeSession(other);
    }
  }

  private QueryResult sql(String statement) {
    return runtime.processor().execute(session, statement);
  }

  /** Membaca langsung dari SM supaya tipe yang benar-benar tersimpan ikut diperiksa. */
  private Value storedGpa(int studentId) {
    try (RowCursor cursor = runtime.storage().openTableScan("student", READ_TX)) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        Row row = next.get().row();
        if (row.values().get(0).equals(new IntValue(studentId))) {
          return row.values().get(GPA);
        }
      }
    }
    throw new AssertionError("student " + studentId + " tidak ditemukan di SM");
  }
}
