package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
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
}
