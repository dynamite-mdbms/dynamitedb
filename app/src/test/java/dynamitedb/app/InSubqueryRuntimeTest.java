package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Subquery IN / NOT IN di WHERE lewat server sungguhan dengan data sampel: student 1..50 (gpa NULL
 * tiap kelipatan 10), course 1..50, dan 100 enrollment yang mencakup semua student.
 */
class InSubqueryRuntimeTest {

  private static final Set<Integer> ALL_STUDENTS =
      IntStream.rangeClosed(1, 50).boxed().collect(Collectors.toCollection(TreeSet::new));

  @TempDir Path directory;

  @Test
  void selectResultsMatchManuallyComputedSets() throws Exception {
    try (Server server = start()) {
      var c = server.client;
      Set<Integer> course1 = set(c, "SELECT student_id FROM enrollment WHERE course_id = 1;");
      String in = "SELECT student_id FROM student WHERE student_id IN ";
      String notIn = "SELECT student_id FROM student WHERE student_id NOT IN ";

      assertEquals(
          course1, set(c, in + "(SELECT student_id FROM enrollment WHERE course_id = 1);"));
      assertEquals(
          minus(ALL_STUDENTS, course1),
          set(c, notIn + "(SELECT student_id FROM enrollment WHERE course_id = 1);"));
      // tiap student ambil dua kelas, tapi IN tidak menggandakan baris
      assertEquals(50, ids(c, in + "(SELECT student_id FROM enrollment);").size());
      assertEquals(0, ids(c, notIn + "(SELECT student_id FROM enrollment);").size());

      // gpa 2.0, 3.0, 4.0 sama dengan id 2, 3, 4; subquery juga berisi NULL
      assertEquals(Set.of(2, 3, 4), set(c, in + "(SELECT gpa FROM student);"));
      assertEquals(0, ids(c, notIn + "(SELECT gpa FROM student);").size());

      // operand NULL (gpa kelipatan 10) tidak pernah lolos IN maupun NOT IN yang tidak kosong
      assertEquals(
          45,
          ids(c, "SELECT student_id FROM student WHERE gpa IN (SELECT gpa FROM student);").size());
      assertEquals(
          0,
          ids(c, "SELECT student_id FROM student WHERE gpa NOT IN (SELECT gpa FROM student);")
              .size());

      // subquery kosong: IN selalu FALSE, NOT IN selalu TRUE termasuk untuk operand NULL
      String empty = "(SELECT gpa FROM student WHERE student_id > 1000);";
      assertEquals(0, ids(c, "SELECT student_id FROM student WHERE gpa IN " + empty).size());
      assertEquals(50, ids(c, "SELECT student_id FROM student WHERE gpa NOT IN " + empty).size());

      // CHAR(1) disimpan dengan padding, tetap cocok dengan status 'C'
      assertEquals(
          set(c, "SELECT student_id FROM student WHERE grade = 'C';"),
          set(c, "SELECT student_id FROM student WHERE grade IN (SELECT status FROM enrollment);"));

      // subquery bersarang
      Set<Integer> twoCredits = set(c, "SELECT course_id FROM course WHERE credits = 2;");
      Set<Integer> nested = new TreeSet<>();
      for (int id : twoCredits) {
        nested.addAll(set(c, "SELECT student_id FROM enrollment WHERE course_id = " + id + ";"));
      }
      assertEquals(
          nested,
          set(
              c,
              in
                  + "(SELECT student_id FROM enrollment WHERE course_id IN"
                  + " (SELECT course_id FROM course WHERE credits = 2));"));

      // digabung AND di kedua sisi dan ORDER BY
      List<Integer> expected =
          ids(c, "SELECT student_id FROM student WHERE gpa > 3 ORDER BY student_id DESC;").stream()
              .filter(course1::contains)
              .toList();
      assertEquals(
          expected,
          ids(
              c,
              "SELECT student_id FROM student WHERE gpa > 3 AND student_id IN"
                  + " (SELECT student_id FROM enrollment WHERE course_id = 1)"
                  + " ORDER BY student_id DESC;"));
      assertEquals(
          expected,
          ids(
              c,
              "SELECT student_id FROM student WHERE student_id IN"
                  + " (SELECT student_id FROM enrollment WHERE course_id = 1) AND gpa > 3"
                  + " ORDER BY student_id DESC;"));

      // IN di kondisi JOIN ... ON
      Set<Integer> coursesOf1 = set(c, "SELECT course_id FROM enrollment WHERE student_id = 1;");
      assertEquals(
          coursesOf1,
          set(
              c,
              "SELECT student.student_id FROM student JOIN course"
                  + " ON student.student_id = course.course_id AND course.course_id IN"
                  + " (SELECT course_id FROM enrollment WHERE student_id = 1);"));
    }
  }

  @Test
  void updateAndDeleteWithInSurviveRestart() throws Exception {
    Set<Integer> course1;
    try (Server server = start()) {
      var c = server.client;
      course1 = set(c, "SELECT student_id FROM enrollment WHERE course_id = 1;");
      var updated =
          c.execute(
              "UPDATE student SET name = 'Course1' WHERE student_id IN"
                  + " (SELECT student_id FROM enrollment WHERE course_id = 1);");
      assertEquals(course1.size(), updated.affectedRows());
      c.execute("INSERT INTO student VALUES (9002, 'Unenrolled', 3.0, 'B');");
      var deleted =
          c.execute(
              "DELETE FROM student WHERE student_id NOT IN (SELECT student_id FROM enrollment);");
      assertEquals(1, deleted.affectedRows());
    }

    try (Server server = start()) {
      var c = server.client;
      assertEquals(course1, set(c, "SELECT student_id FROM student WHERE name = 'Course1';"));
      assertEquals(ALL_STUDENTS, set(c, "SELECT student_id FROM student;"));
    }
  }

  @Test
  void inWorksInsideExplicitTransactionsAndAbortRollsBack() throws Exception {
    try (Server server = start()) {
      var c = server.client;
      String delete =
          "DELETE FROM student WHERE student_id IN"
              + " (SELECT student_id FROM enrollment WHERE course_id = 1);";
      Set<Integer> course1 = set(c, "SELECT student_id FROM enrollment WHERE course_id = 1;");

      c.execute("BEGIN;");
      assertEquals(course1.size(), c.execute(delete).affectedRows());
      assertEquals(minus(ALL_STUDENTS, course1), set(c, "SELECT student_id FROM student;"));
      c.execute("ABORT;");
      assertEquals(ALL_STUDENTS, set(c, "SELECT student_id FROM student;"));

      c.execute("BEGIN;");
      c.execute(delete);
      c.execute("COMMIT;");
      assertEquals(minus(ALL_STUDENTS, course1), set(c, "SELECT student_id FROM student;"));
      assertEquals(
          0,
          ids(
                  c,
                  "SELECT student_id FROM student WHERE student_id IN"
                      + " (SELECT student_id FROM enrollment WHERE course_id = 1);")
              .size());
    }
  }

  @Test
  void rejectedFormsReturnTheirErrorCodes() throws Exception {
    try (Server server = start()) {
      var c = server.client;
      String select = "SELECT student_id FROM student WHERE ";
      assertCode(c, ErrorCode.SQL_SYNTAX, select + "student_id IN (1, 2);");
      assertCode(c, ErrorCode.SQL_SYNTAX, select + "student_id IN ();");
      assertCode(c, ErrorCode.SQL_SYNTAX, select + "student_id IN SELECT student_id FROM course;");
      assertCode(c, ErrorCode.SQL_SYNTAX, select + "student_id = (SELECT course_id FROM course);");
      assertCode(c, ErrorCode.SQL_SYNTAX, select + "EXISTS (SELECT course_id FROM course);");
      assertCode(
          c,
          ErrorCode.SQL_BIND,
          select + "student_id IN (SELECT course_id FROM course WHERE credits = gpa);");
      assertCode(c, ErrorCode.SQL_BIND, select + "student_id IN (SELECT * FROM course);");
      assertCode(c, ErrorCode.SQL_BIND, select + "name IN (SELECT course_id FROM course);");
      assertCode(c, ErrorCode.SQL_BIND, select + "student_id IN (SELECT x FROM nowhere);");
      // koneksi tetap bisa dipakai setelah error
      assertEquals(50, ids(c, "SELECT student_id FROM student;").size());
    }
  }

  private Server start() throws Exception {
    return new Server(new DbmsConfig(0, directory.resolve("db"), ProtocolType.STRICT_2PL));
  }

  private static void assertCode(RemoteQueryClient client, ErrorCode code, String sql) {
    var error = assertThrows(DbmsException.class, () -> client.execute(sql), sql);
    assertEquals(code, error.getCode(), sql + " -> " + error.getMessage());
  }

  private static List<Integer> ids(RemoteQueryClient client, String sql) throws Exception {
    return client.execute(sql).rows().stream()
        .map(row -> ((IntValue) row.values().get(0)).value())
        .toList();
  }

  private static Set<Integer> set(RemoteQueryClient client, String sql) throws Exception {
    return new TreeSet<>(ids(client, sql));
  }

  private static Set<Integer> minus(Set<Integer> all, Set<Integer> removed) {
    Set<Integer> result = new TreeSet<>(all);
    result.removeAll(removed);
    return result;
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
