package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.storage.FloatValue;
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
  void negativeNumericLiteralsWorkInWhereInsertAndUpdate() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        assertEquals(
            0,
            client.execute("SELECT student_id FROM student WHERE student_id = -1;").rows().size());

        client.execute("INSERT INTO student VALUES (-7, 'Negative', -2.5, 'A');");
        client.execute("UPDATE student SET gpa = -1.5 WHERE student_id = -7;");
        assertEquals(
            new FloatValue(-1.5),
            client
                .execute("SELECT gpa FROM student WHERE student_id = -7;")
                .rows()
                .get(0)
                .values()
                .get(0));
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
}
