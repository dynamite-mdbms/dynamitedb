package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
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

  private static int count(RemoteQueryClient client, String sql) throws Exception {
    return client.execute(sql).rows().size();
  }

  private static Value first(RemoteQueryClient client, String sql) throws Exception {
    return client.execute(sql).rows().get(0).values().get(0);
  }
}
