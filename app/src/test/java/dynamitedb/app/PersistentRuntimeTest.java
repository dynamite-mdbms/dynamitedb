package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.ProtocolType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentRuntimeTest {
  @TempDir Path directory;

  @Test
  void committedSqlWritesSurvivePersistentRuntimeRestart() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        client.execute("INSERT INTO student VALUES (9001, 'Persisted', 3.5, 'A');");
        assertEquals(
            1,
            client
                .execute("SELECT student_id FROM student WHERE student_id = 9001;")
                .rows()
                .size());
      }
    }

    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        assertEquals(
            1,
            client
                .execute("SELECT student_id FROM student WHERE student_id = 9001;")
                .rows()
                .size());
      }
    }
  }

  private static final String HUGE = "9".repeat(400) + ".0";
  private static final String GPA_OF_1 = "SELECT gpa FROM student WHERE student_id = 1;";

  @Test
  void floatLiteralOverflowIsRejectedBeforeUpdateIsPersisted() throws Exception {
    List<Row> before =
        session(
            client -> {
              List<Row> rows = client.execute(GPA_OF_1).rows();
              assertEquals(1, rows.size());
              assertSyntaxError(
                  client, "UPDATE student SET gpa = " + HUGE + " WHERE student_id = 1;");
              return rows;
            });
    assertEquals(before, session(client -> client.execute(GPA_OF_1).rows()));
  }

  @Test
  void insertWithOverflowingFloatIsRejectedAndNotPersisted() throws Exception {
    String select = "SELECT student_id FROM student WHERE student_id = 9002;";
    session(
        client -> {
          assertSyntaxError(
              client, "INSERT INTO student VALUES (9002, 'Huge', " + HUGE + ", 'A');");
          assertEquals(0, client.execute(select).rows().size());
          return null;
        });
    assertEquals(List.of(), session(client -> client.execute(select).rows()));
  }

  @Test
  void selectWithOverflowingFloatIsRejected() throws Exception {
    session(
        client -> {
          assertSyntaxError(client, "SELECT * FROM student WHERE gpa = " + HUGE + ";");
          return null;
        });
  }

  @Test
  void syntaxErrorInsideTransactionKeepsTransactionOpenAndUsable() throws Exception {
    session(
        client -> {
          client.execute("BEGIN TRANSACTION;");
          client.execute("UPDATE student SET gpa = 3.25 WHERE student_id = 1;");
          assertSyntaxError(client, "UPDATE student SET gpa = " + HUGE + " WHERE student_id = 1;");
          // Error saat parsing tidak meng-abort transaksi: perubahan sebelumnya masih terlihat.
          assertEquals(gpa(3.25), client.execute(GPA_OF_1).rows());
          client.execute("COMMIT;");
          return null;
        });
    assertEquals(gpa(3.25), session(client -> client.execute(GPA_OF_1).rows()));
  }

  @Test
  void doubleMaxValueLiteralIsAcceptedAndPersisted() throws Exception {
    String max = new BigDecimal(Double.MAX_VALUE).toPlainString() + ".0";
    session(
        client -> {
          assertEquals(
              1,
              client
                  .execute("UPDATE student SET gpa = " + max + " WHERE student_id = 1;")
                  .affectedRows());
          return null;
        });
    assertEquals(gpa(Double.MAX_VALUE), session(client -> client.execute(GPA_OF_1).rows()));
  }

  @Test
  void negativeNumericLiteralsWorkInWhereInsertUpdateDeleteAndSurviveRestart() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    String gpaOfMinusSeven = "SELECT gpa FROM student WHERE student_id = -7;";
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        assertEquals(0, count(client, "SELECT student_id FROM student WHERE student_id = -1;"));
        assertEquals(50, count(client, "SELECT student_id FROM student WHERE student_id > -1;"));
        assertEquals(0, count(client, "SELECT student_id FROM student WHERE student_id < -1;"));

        client.execute("INSERT INTO student VALUES (-7, 'Negative', -2.5, 'A');");
        client.execute("UPDATE student SET gpa = -1.5 WHERE student_id = -7;");
        assertEquals(new FloatValue(-1.5), first(client, gpaOfMinusSeven));
        client.execute("UPDATE student SET gpa = gpa - -1 WHERE student_id = -7;");
        assertEquals(new FloatValue(-0.5), first(client, gpaOfMinusSeven));

        client.execute("INSERT INTO student VALUES (-9, 'Gone', -1.0, 'B');");
        client.execute("DELETE FROM student WHERE student_id = -9;");
        assertEquals(0, count(client, "SELECT student_id FROM student WHERE student_id = -9;"));

        // Batas negatif harus tetap benar saat akses lewat indeks.
        client.execute("CREATE INDEX idx_student_id ON student(student_id) USING B_PLUS_TREE;");
        assertEquals(1, count(client, "SELECT student_id FROM student WHERE student_id < 0;"));
        assertEquals(1, count(client, "SELECT student_id FROM student WHERE student_id = -7;"));
        assertEquals(51, count(client, "SELECT student_id FROM student WHERE student_id >= -7;"));
      }
    }

    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        assertEquals(new FloatValue(-0.5), first(client, gpaOfMinusSeven));
        assertEquals(0, count(client, "SELECT student_id FROM student WHERE student_id = -9;"));
        assertEquals(50, count(client, "SELECT student_id FROM student WHERE student_id > -1;"));
      }
    }
  }

  @Test
  void negativeZeroMatchesStoredZero() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        client.execute("INSERT INTO student VALUES (-8, 'Zero', 0.0, 'A');");
        assertEquals(
            1, client.execute("SELECT student_id FROM student WHERE gpa = -0.0;").rows().size());
      }
    }
  }

  /** Menyalakan runtime baru di direktori yang sama, sehingga tiap panggilan setara restart. */
  private <T> T session(ClientAction<T> action) throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        return action.run(client);
      }
    }
  }

  private static void assertSyntaxError(RemoteQueryClient client, String sql) {
    assertEquals(
        ErrorCode.SQL_SYNTAX,
        assertThrows(DbmsException.class, () -> client.execute(sql)).getCode());
  }

  private static List<Row> gpa(double value) {
    return List.of(new Row(List.of(new FloatValue(value))));
  }

  private interface ClientAction<T> {
    T run(RemoteQueryClient client) throws Exception;
  }

  private static int count(RemoteQueryClient client, String sql) throws Exception {
    return client.execute(sql).rows().size();
  }

  private static Value first(RemoteQueryClient client, String sql) throws Exception {
    return client.execute(sql).rows().get(0).values().get(0);
  }
}
