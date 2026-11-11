package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import java.util.List;
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
}
