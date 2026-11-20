package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Optional SQL execution added for QP #106, exercised through the packaged server runtime. */
class OptionalExecutionRuntimeTest {

  @TempDir Path directory;

  @Test
  void threeValuedOrAndNotWorkThroughRuntime() throws Exception {
    try (Server server = start()) {
      var client = server.client;
      assertEquals(
          6,
          client
              .execute("SELECT student_id FROM student WHERE gpa IS NULL OR student_id = 1;")
              .rows()
              .size());
      assertEquals(
          45,
          client.execute("SELECT student_id FROM student WHERE NOT (gpa IS NULL);").rows().size());
      assertEquals(
          1,
          client
              .execute("SELECT student_id FROM student WHERE (gpa = NULL) OR student_id = 1;")
              .rows()
              .size());
      assertEquals(
          0,
          client.execute("SELECT student_id FROM student WHERE NOT (gpa = NULL);").rows().size());
      assertCode(
          client, ErrorCode.SQL_BIND, "SELECT student_id FROM student WHERE NOT student_id;");
    }
  }

  @Test
  void aggregatesHandleEmptyNullGroupedSortedAndLimitedResults() throws Exception {
    try (Server server = start()) {
      var client = server.client;
      client.execute(
          "CREATE TABLE optional_metrics (id INTEGER PRIMARY KEY, group_id INTEGER, val INTEGER)");

      var empty = client.execute("SELECT COUNT(*), COUNT(val), SUM(val) FROM optional_metrics;");
      assertEquals(1, empty.rows().size());
      assertEquals(
          List.of(new IntValue(0), new IntValue(0), NullValue.INSTANCE),
          empty.rows().getFirst().values());

      client.execute("INSERT INTO optional_metrics VALUES (1, 1, 10);");
      client.execute("INSERT INTO optional_metrics VALUES (2, 1, NULL);");
      client.execute("INSERT INTO optional_metrics VALUES (3, 2, 20);");

      var grouped =
          client.execute(
              "SELECT group_id, SUM(val) AS total, COUNT(val) AS n"
                  + " FROM optional_metrics GROUP BY group_id ORDER BY total DESC LIMIT 1;");
      assertEquals(
          List.of(new IntValue(2), new IntValue(20), new IntValue(1)),
          grouped.rows().getFirst().values());

      client.execute("INSERT INTO optional_metrics VALUES (4, 3, 2147483647);");
      client.execute("INSERT INTO optional_metrics VALUES (5, 3, 1);");
      assertCode(
          client, ErrorCode.INVALID_STATE, "SELECT SUM(val) FROM optional_metrics WHERE id >= 4;");
    }
  }

  @Test
  void multiAssignmentUpdateSwapsValuesAndFailureLeavesRowsUntouched() throws Exception {
    try (Server server = start()) {
      var client = server.client;
      client.execute(
          "CREATE TABLE assignment_pairs (id INTEGER PRIMARY KEY, left_value INTEGER,"
              + " right_value INTEGER)");
      client.execute("INSERT INTO assignment_pairs VALUES (1, 10, 20);");
      client.execute("INSERT INTO assignment_pairs VALUES (2, 30, 40);");
      client.execute("INSERT INTO assignment_pairs VALUES (3, 50, 60);");

      assertEquals(
          3,
          client
              .execute(
                  "UPDATE assignment_pairs SET left_value = right_value,"
                      + " right_value = left_value;")
              .affectedRows());
      var afterSwap =
          client.execute("SELECT left_value, right_value FROM assignment_pairs ORDER BY id;");
      assertEquals(
          List.of(
              List.of(new IntValue(20), new IntValue(10)),
              List.of(new IntValue(40), new IntValue(30)),
              List.of(new IntValue(60), new IntValue(50))),
          afterSwap.rows().stream().map(row -> row.values()).toList());

      assertCode(
          client,
          ErrorCode.INVALID_STATE,
          "UPDATE assignment_pairs SET left_value = 100 / (id - 2),"
              + " right_value = left_value;");
      var afterFailure =
          client.execute("SELECT left_value, right_value FROM assignment_pairs ORDER BY id;");
      assertEquals(afterSwap.rows(), afterFailure.rows());

      assertEquals(
          3,
          client
              .execute(
                  "UPDATE assignment_pairs SET left_value ="
                      + " (SELECT course_id FROM course WHERE course_id = 1),"
                      + " right_value = left_value;")
              .affectedRows());
      assertEquals(
          List.of(
              List.of(new IntValue(1), new IntValue(20)),
              List.of(new IntValue(1), new IntValue(40)),
              List.of(new IntValue(1), new IntValue(60))),
          client
              .execute("SELECT left_value, right_value FROM assignment_pairs ORDER BY id;")
              .rows()
              .stream()
              .map(row -> row.values())
              .toList());
    }
  }

  @Test
  void scalarAndExistsSubqueriesWorkInPredicatesProjectionsAndJoins() throws Exception {
    try (Server server = start()) {
      var client = server.client;
      assertEquals(
          List.of(new IntValue(1)),
          client
              .execute(
                  "SELECT student_id FROM student WHERE student_id ="
                      + " (SELECT course_id FROM course WHERE course_id = 1);")
              .rows()
              .stream()
              .map(row -> row.values().getFirst())
              .toList());
      assertEquals(
          List.of(new IntValue(1)),
          client
              .execute(
                  "SELECT (SELECT course_id FROM course WHERE course_id = 1)"
                      + " FROM student WHERE student_id = 1;")
              .rows()
              .stream()
              .map(row -> row.values().getFirst())
              .toList());
      assertEquals(
          0,
          client
              .execute(
                  "SELECT student_id FROM student WHERE student_id ="
                      + " (SELECT course_id FROM course WHERE course_id > 1000);")
              .rows()
              .size());
      assertCode(
          client,
          ErrorCode.INVALID_STATE,
          "SELECT student_id FROM student WHERE student_id ="
              + " (SELECT course_id FROM course WHERE course_id < 3);");

      assertEquals(
          50,
          client
              .execute(
                  "SELECT student_id FROM student WHERE EXISTS"
                      + " (SELECT course_id FROM course WHERE course_id = 1);")
              .rows()
              .size());
      assertEquals(
          0,
          client
              .execute(
                  "SELECT student_id FROM student WHERE EXISTS"
                      + " (SELECT course_id FROM course WHERE course_id > 1000);")
              .rows()
              .size());
      assertEquals(
          50,
          client
              .execute(
                  "SELECT student_id FROM student WHERE NOT EXISTS"
                      + " (SELECT course_id FROM course WHERE course_id > 1000);")
              .rows()
              .size());
      assertEquals(
          1,
          client
              .execute(
                  "SELECT student.student_id FROM student JOIN course"
                      + " ON student.student_id = course.course_id"
                      + " WHERE EXISTS (SELECT course_id FROM enrollment WHERE course_id = 1)"
                      + " ORDER BY student.student_id LIMIT 1;")
              .rows()
              .size());

      client.execute("BEGIN;");
      assertEquals(
          List.of(new IntValue(1)),
          client
              .execute(
                  "SELECT student_id FROM student WHERE student_id ="
                      + " (SELECT course_id FROM course WHERE course_id = 1);")
              .rows()
              .stream()
              .map(row -> row.values().getFirst())
              .toList());
      assertEquals(
          50,
          client
              .execute(
                  "SELECT student_id FROM student WHERE EXISTS"
                      + " (SELECT course_id FROM course WHERE course_id = 1);")
              .rows()
              .size());
      client.execute("COMMIT;");
    }
  }

  private Server start() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL);
    return new Server(config);
  }

  private static void assertCode(RemoteQueryClient client, ErrorCode code, String sql) {
    DbmsException error = assertThrows(DbmsException.class, () -> client.execute(sql), sql);
    assertEquals(code, error.getCode(), sql + " -> " + error.getMessage());
  }

  private static final class Server implements AutoCloseable {
    private final PersistentRuntime runtime;
    private final RemoteQueryClient client;

    private Server(DbmsConfig config) throws Exception {
      runtime = new PersistentRuntime(config);
      runtime.bootstrap().start(config);
      client = new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port());
    }

    @Override
    public void close() throws java.io.IOException {
      try (runtime) {
        client.close();
      }
    }
  }
}
