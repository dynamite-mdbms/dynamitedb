package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.ProtocolType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
      assertTrue(runtime.checkpoint().checkpointLsn().value() > 0);
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

  @Test
  void liveRuntimeExposesCheckpointLogInspectionHistoricalRecoveryAndRealRollback()
      throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        client.execute("BEGIN;");
        client.execute("DELETE FROM student WHERE student_id = 1;");
        client.execute("ABORT;");
        assertEquals(1, count(client, "SELECT student_id FROM student WHERE student_id = 1;"));

        var beforeInsert = runtime.readLog(new LogFilter(Optional.empty(), Optional.empty()));
        var recoveryPoint = lastCommittedTransaction(beforeInsert.lines());
        client.execute("INSERT INTO student VALUES (99001, 'Recover me', 3.0, 'A');");

        var logAfterInsert = runtime.readLog(new LogFilter(Optional.empty(), Optional.empty()));
        assertTrue(
            logAfterInsert.lines().stream()
                .anyMatch(line -> line.contains("INSERT table=student")));
        assertTrue(runtime.checkpoint().checkpointLsn().value() > 0);

        var restored =
            runtime.recoverTo(new RecoveryTarget(Optional.empty(), Optional.of(recoveryPoint)));
        assertEquals(RecoveryStatus.SUCCESS, restored.status(), restored.message());
        assertEquals(0, count(client, "SELECT student_id FROM student WHERE student_id = 99001;"));

        client.execute("INSERT INTO student VALUES (99001, 'After restore', 3.0, 'A');");
        assertEquals(1, count(client, "SELECT student_id FROM student WHERE student_id = 99001;"));
      }
    }
  }

  @Test
  void nullLiteralsWorkOnPersistentRuntime() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        client.execute("INSERT INTO student VALUES (9003, 'Null GPA', NULL, 'A');");
        var rows = client.execute("SELECT gpa FROM student WHERE student_id = 9003;").rows();
        assertEquals(1, rows.size());
        assertEquals(NullValue.INSTANCE, rows.get(0).values().get(0));

        // perbandingan dengan NULL tidak pernah bernilai true
        assertEquals(
            0, client.execute("SELECT student_id FROM student WHERE gpa = NULL;").rows().size());

        // data contoh juga punya gpa NULL, jadi cek keanggotaan dan partisinya
        var nullGpa = client.execute("SELECT student_id FROM student WHERE gpa IS NULL;").rows();
        assertTrue(nullGpa.stream().anyMatch(r -> r.values().get(0).equals(new IntValue(9003))));
        var notNullGpa =
            client.execute("SELECT student_id FROM student WHERE gpa IS NOT NULL;").rows();
        assertTrue(
            notNullGpa.stream().noneMatch(r -> r.values().get(0).equals(new IntValue(9003))));
        assertEquals(
            client.execute("SELECT student_id FROM student;").rows().size(),
            nullGpa.size() + notNullGpa.size());

        client.execute("UPDATE student SET grade = NULL WHERE student_id = 9003;");
        assertEquals(
            NullValue.INSTANCE,
            client
                .execute("SELECT grade FROM student WHERE student_id = 9003;")
                .rows()
                .get(0)
                .values()
                .get(0));

        var error =
            assertThrows(
                DbmsException.class,
                () -> client.execute("INSERT INTO student VALUES (NULL, 'No Id', 3.0, 'B');"));
        assertEquals(ErrorCode.SQL_BIND, error.getCode());
      }
    }
  }

  @Test
  void updateExpressionYieldingNullIsRejectedForNotNullColumn() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config);
        RemoteQueryClient client = connect(runtime, config)) {
      var error =
          assertThrows(
              DbmsException.class,
              () ->
                  client.execute(
                      "UPDATE student SET student_id = student_id + NULL WHERE student_id = 1;"));
      assertEquals(ErrorCode.SQL_BIND, error.getCode());
      assertTrue(error.getMessage().contains("student_id"), error.getMessage());
      assertEquals(
          1, client.execute("SELECT name FROM student WHERE student_id = 1;").rows().size());
      // kolom nullable tetap boleh menerima hasil ekspresi NULL
      client.execute("UPDATE student SET gpa = gpa + NULL WHERE student_id = 1;");
      assertEquals(
          NullValue.INSTANCE,
          client
              .execute("SELECT gpa FROM student WHERE student_id = 1;")
              .rows()
              .get(0)
              .values()
              .get(0));
    }
  }

  @Test
  void nullValuesSurvivePersistentRuntimeRestart() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config);
        RemoteQueryClient client = connect(runtime, config)) {
      client.execute("INSERT INTO student VALUES (9004, 'Restart', NULL, 'B');");
      client.execute("UPDATE student SET grade = NULL WHERE student_id = 9004;");
    }
    try (PersistentRuntime runtime = new PersistentRuntime(config);
        RemoteQueryClient client = connect(runtime, config)) {
      var row =
          client.execute("SELECT gpa, grade FROM student WHERE student_id = 9004;").rows().get(0);
      assertEquals(List.of(NullValue.INSTANCE, NullValue.INSTANCE), row.values());
      assertTrue(ids(client, "SELECT student_id FROM student WHERE gpa IS NULL;").contains(9004));
    }
  }

  @Test
  void isNullFiltersSelectUpdateAndDeleteOnSampleData() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config);
        RemoteQueryClient client = connect(runtime, config)) {
      // data contoh: gpa NULL untuk setiap student ke-10
      assertEquals(
          Set.of(10, 20, 30, 40, 50),
          ids(client, "SELECT student_id FROM student WHERE gpa IS NULL;"));
      assertEquals(
          Set.of(30, 40, 50),
          ids(client, "SELECT student_id FROM student WHERE gpa IS NULL AND student_id > 25;"));
      assertEquals(0, ids(client, "SELECT student_id FROM student WHERE NULL = NULL;").size());
      assertEquals(0, ids(client, "SELECT student_id FROM student WHERE gpa <> NULL;").size());

      assertEquals(
          3,
          client
              .execute("UPDATE student SET gpa = 3.0 WHERE gpa IS NULL AND student_id > 25;")
              .affectedRows());
      assertEquals(
          new FloatValue(3.0),
          client
              .execute("SELECT gpa FROM student WHERE student_id = 40;")
              .rows()
              .get(0)
              .values()
              .get(0));
      assertEquals(2, client.execute("DELETE FROM student WHERE gpa IS NULL;").affectedRows());
      assertEquals(0, ids(client, "SELECT student_id FROM student WHERE gpa IS NULL;").size());
      assertEquals(48, ids(client, "SELECT student_id FROM student;").size());
      assertEquals(
          Set.of(30, 31, 40, 49, 50),
          ids(client, "SELECT student_id FROM student WHERE gpa = 3.0 AND student_id > 25;"));
    }
  }

  @Test
  void isNullOnIndexedColumnAndAcrossJoin() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config);
        RemoteQueryClient client = connect(runtime, config)) {
      client.execute("CREATE INDEX idx_student_gpa ON student(gpa) USING B_PLUS_TREE;");
      client.execute("CREATE INDEX idx_enrollment_score ON enrollment(score);");
      assertEquals(
          Set.of(10, 20, 30, 40, 50),
          ids(client, "SELECT student_id FROM student WHERE gpa IS NULL;"));
      assertEquals(45, ids(client, "SELECT student_id FROM student WHERE gpa IS NOT NULL;").size());
      // index tetap dipakai untuk kesetaraan biasa
      assertEquals(
          Set.of(4, 13, 22, 31, 49),
          ids(client, "SELECT student_id FROM student WHERE gpa = 3.0;"));

      // data contoh: score NULL untuk enrollment ke-i dengan i % 9 == 0 (12 baris)
      String join =
          "SELECT student.student_id FROM enrollment JOIN student"
              + " ON enrollment.student_id = student.student_id WHERE enrollment.score IS NULL";
      assertEquals(12, client.execute(join + ";").rows().size());
      assertEquals(Set.of(10, 50), ids(client, join + " AND student.gpa IS NULL;"));
    }
  }

  private static Set<Integer> ids(RemoteQueryClient client, String sql) throws Exception {
    return client.execute(sql).rows().stream()
        .map(row -> ((IntValue) row.values().get(0)).value())
        .collect(Collectors.toSet());
  }

  private static RemoteQueryClient connect(PersistentRuntime runtime, DbmsConfig config)
      throws Exception {
    runtime.bootstrap().start(config);
    return new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port());
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

  private static dynamitedb.contracts.transaction.TransactionId lastCommittedTransaction(
      List<String> lines) {
    dynamitedb.contracts.transaction.TransactionId latest = null;
    for (String line : lines) {
      if (!line.contains(" COMMIT")) continue;
      int txStart = line.indexOf("tx=") + 3;
      int txEnd = line.indexOf(' ', txStart);
      latest =
          new dynamitedb.contracts.transaction.TransactionId(
              Long.parseLong(line.substring(txStart + 1, txEnd)));
    }
    if (latest == null) throw new AssertionError("Expected at least one committed transaction");
    return latest;
  }

  private static Value first(RemoteQueryClient client, String sql) throws Exception {
    return client.execute(sql).rows().get(0).values().get(0);
  }
}
