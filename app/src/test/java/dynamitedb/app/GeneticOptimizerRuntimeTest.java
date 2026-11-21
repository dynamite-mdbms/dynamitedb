package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dynamitedb.app.client.RemoteQueryClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneticOptimizerRuntimeTest {
  private static final String JOIN =
      "SELECT student.student_id, course.course_id FROM student"
          + " JOIN enrollment ON student.student_id = enrollment.student_id"
          + " JOIN course ON enrollment.course_id = course.course_id";

  @TempDir Path directory;

  @Test
  void geneticOptimizerReturnsTheSameRowsAsTheHeuristicOptimizer() throws Exception {
    List<String> heuristic = rows(false, JOIN);
    List<String> genetic = rows(true, JOIN);

    assertFalse(heuristic.isEmpty());
    assertEquals(heuristic, genetic);
  }

  /** Rows as sorted strings, so different join orders compare as multisets. */
  private List<String> rows(boolean geneticOptimizer, String sql) throws Exception {
    DbmsConfig config =
        new DbmsConfig(
            0, directory.resolve("data-" + geneticOptimizer), DbmsConfig.defaults().protocol());
    try (PersistentRuntime runtime = new PersistentRuntime(config, geneticOptimizer)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client =
          new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port())) {
        return client.execute(sql).rows().stream().map(Object::toString).sorted().toList();
      }
    }
  }
}
