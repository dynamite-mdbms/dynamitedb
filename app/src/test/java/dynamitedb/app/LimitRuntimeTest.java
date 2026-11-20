package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.IntValue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LimitRuntimeTest {
  @TempDir Path directory;

  private PersistentRuntime runtime;
  private RemoteQueryClient client;

  @BeforeEach
  void start() throws Exception {
    DbmsConfig config =
        new DbmsConfig(0, directory.resolve("data"), DbmsConfig.defaults().protocol());
    runtime = new PersistentRuntime(config);
    runtime.bootstrap().start(config);
    client = new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port());
  }

  @AfterEach
  void stop() throws IOException {
    try {
      if (client != null) client.close();
    } finally {
      if (runtime != null) runtime.close();
    }
  }

  @Test
  void limitsRowsAndPreservesSchemaWhenZeroOrEmpty() throws IOException {
    QueryResult all = sql("SELECT student_id FROM student");
    QueryResult zero = sql("SELECT student_id FROM student LIMIT 0");
    QueryResult one = sql("SELECT student_id FROM student LIMIT 1");
    QueryResult larger = sql("SELECT student_id FROM student LIMIT 1000");
    QueryResult empty = sql("SELECT student_id FROM student WHERE student_id = -1 LIMIT 1");

    assertEquals(all.schema(), zero.schema());
    assertTrue(zero.rows().isEmpty());
    assertEquals(1, one.rows().size());
    assertEquals(all.rows(), larger.rows());
    assertEquals(all.schema(), empty.schema());
    assertTrue(empty.rows().isEmpty());
  }

  @Test
  void appliesLimitAfterJoinsAndAscendingOrDescendingSorts() throws IOException {
    assertEquals(
        2,
        sql("SELECT student.student_id FROM student JOIN enrollment"
                + " ON student.student_id = enrollment.student_id LIMIT 2")
            .rows()
            .size());
    assertEquals(
        List.of(new IntValue(1), new IntValue(2)),
        sql("SELECT student_id FROM student ORDER BY student_id ASC LIMIT 2").rows().stream()
            .map(row -> row.values().get(0))
            .toList());
    assertEquals(
        List.of(new IntValue(50), new IntValue(49)),
        sql("SELECT student_id FROM student ORDER BY student_id DESC LIMIT 2").rows().stream()
            .map(row -> row.values().get(0))
            .toList());
  }

  private QueryResult sql(String statement) throws IOException {
    return client.execute(statement);
  }
}
