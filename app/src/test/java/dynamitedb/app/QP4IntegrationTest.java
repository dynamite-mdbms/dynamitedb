package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.sm.catalog.SampleDatabaseLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QP4IntegrationTest {

  @TempDir Path directory;

  private StubRuntime runtime;
  private SessionId session;

  @BeforeEach
  void start() throws Exception {
    start(DbmsConfig.defaults().protocol(), "data");
  }

  @AfterEach
  void stop() {
    if (runtime == null) {
      return;
    }
    try {
      runtime.processor().closeSession(session);
    } finally {
      runtime.bootstrap().shutdown();
      runtime = null;
    }
  }

  // JOIN (QP-08)

  @Test
  @DisplayName("Cartesian Product menghasilkan seluruh kombinasi pasangan baris (50 x 50)")
  void cartesianProducesAllPairs() {
    QueryResult result = sql("SELECT * FROM student, course");

    assertEquals(2500, result.rows().size());
    assertEquals(8, result.schema().columns().size());
    int studentId = indexOf(result, "student", "student_id");
    int courseId = indexOf(result, "course", "course_id");
    Set<String> pairs = new HashSet<>();
    for (Row row : result.rows()) {
      pairs.add(intAt(row, studentId) + "-" + intAt(row, courseId));
    }
    assertEquals(2500, pairs.size(), "setiap pasangan student-course harus muncul tepat sekali");
  }

  @Test
  @DisplayName("Conditional Join hanya mengeluarkan pasangan yang memenuhi kondisi ON")
  void conditionalJoinMatchesCondition() {
    QueryResult result =
        sql("SELECT * FROM student JOIN enrollment ON student.student_id = enrollment.student_id");

    assertEquals(100, result.rows().size());
    assertEquals(8, result.schema().columns().size());
    int left = indexOf(result, "student", "student_id");
    int right = indexOf(result, "enrollment", "student_id");
    for (Row row : result.rows()) {
      assertEquals(intAt(row, left), intAt(row, right));
    }
  }

  @Test
  @DisplayName("Urutan tabel di query menentukan urutan kolom hasil, walau optimizer menukar join")
  void swappedTablesKeepQueryColumnOrder() {
    QueryResult result =
        sql("SELECT * FROM enrollment JOIN student ON student.student_id = enrollment.student_id");

    assertEquals(100, result.rows().size());
    List<Column> columns = result.schema().columns();
    assertEquals(8, columns.size());
    for (int i = 0; i < 4; i++) {
      assertTrue(
          "enrollment".equalsIgnoreCase(columns.get(i).relation()),
          "kolom ke-" + i + " harus milik enrollment, tetapi " + columns.get(i));
    }
    for (int i = 4; i < 8; i++) {
      assertTrue(
          "student".equalsIgnoreCase(columns.get(i).relation()),
          "kolom ke-" + i + " harus milik student, tetapi " + columns.get(i));
    }
  }

  @Test
  @DisplayName("Natural Join mencocokkan kolom bernama sama dan mengeliminasi kolom duplikat")
  void naturalJoinDropsDuplicateColumn() {
    QueryResult result = sql("SELECT * FROM student NATURAL JOIN enrollment");

    assertEquals(100, result.rows().size());
    assertEquals(7, result.schema().columns().size()); // 4 + 4 - 1 kolom student_id
    assertEquals(1, countColumnsNamed(result, "student_id"));
  }

  @Test
  @DisplayName("Natural Join tanpa kolom bersama berperilaku seperti Cartesian Product")
  void naturalJoinWithoutCommonColumnIsCartesian() {
    QueryResult result = sql("SELECT * FROM student NATURAL JOIN course");

    assertEquals(2500, result.rows().size());
    assertEquals(8, result.schema().columns().size());
  }

  @Test
  @DisplayName("Kolom hasil join dapat diakses dengan prefix nama tabel (tabel.kolom)")
  void prefixedColumnsAreAccessible() {
    QueryResult result =
        sql(
            "SELECT student.student_id, enrollment.student_id FROM student JOIN enrollment"
                + " ON student.student_id = enrollment.student_id");

    assertEquals(100, result.rows().size());
    List<Column> columns = result.schema().columns();
    assertEquals(2, columns.size());
    assertTrue("student".equalsIgnoreCase(columns.get(0).relation()));
    assertTrue("enrollment".equalsIgnoreCase(columns.get(1).relation()));
    for (Row row : result.rows()) {
      assertEquals(intAt(row, 0), intAt(row, 1));
    }
  }

  @Test
  @DisplayName("Kolom tanpa prefix yang dimiliki dua tabel ditolak sebagai ambigu")
  void ambiguousColumnIsRejected() {
    DbmsException error =
        assertThrows(
            DbmsException.class,
            () ->
                sql(
                    "SELECT student_id FROM student JOIN enrollment"
                        + " ON student.student_id = enrollment.student_id"));

    assertEquals(ErrorCode.SQL_BIND, error.getCode());
  }

  @Test
  @DisplayName("Conditional Join tiga tabel")
  void threeTableConditionalJoin() {
    QueryResult result =
        sql(
            "SELECT * FROM student JOIN enrollment ON student.student_id = enrollment.student_id"
                + " JOIN course ON enrollment.course_id = course.course_id");

    assertEquals(100, result.rows().size());
    assertEquals(12, result.schema().columns().size());
    int enrollmentCourse = indexOf(result, "enrollment", "course_id");
    int courseId = indexOf(result, "course", "course_id");
    for (Row row : result.rows()) {
      assertEquals(intAt(row, enrollmentCourse), intAt(row, courseId));
    }
  }

  @Test
  @DisplayName("Natural Join tiga tabel mengeliminasi setiap kolom duplikat")
  void threeTableNaturalJoin() {
    QueryResult result = sql("SELECT * FROM student NATURAL JOIN enrollment NATURAL JOIN course");

    assertEquals(100, result.rows().size());
    assertEquals(10, result.schema().columns().size()); // 4 + 3 + 3
    assertEquals(1, countColumnsNamed(result, "student_id"));
    assertEquals(1, countColumnsNamed(result, "course_id"));
  }

  @Test
  @DisplayName(
      "BNLJ dengan relasi lebih dari satu blok (lebih dari 100 baris) di setiap protokol CCM")
  void multiBlockJoinUnderEveryProtocol() throws Exception {
    for (ProtocolType protocol : ProtocolType.values()) {
      stop();
      start(protocol, "data-" + protocol.name().toLowerCase());

      // enrollment jadi 160 baris: student 1-10 punya 4 baris, student 11-50 punya 3 baris
      for (int k = 0; k < 60; k++) {
        QueryResult insert =
            sql(
                "INSERT INTO enrollment VALUES ("
                    + (k % 50 + 1)
                    + ", "
                    + ((k * 7) % 50 + 1)
                    + ", 70.5, 'C')");
        assertEquals(1, insert.affectedRows(), protocol + ": INSERT ke-" + k);
      }

      assertEquals(
          160,
          sql("SELECT * FROM enrollment NATURAL JOIN student").rows().size(),
          protocol + ": enrollment NATURAL JOIN student");
      // sum(jumlah^2) = 10 * 4^2 + 40 * 3^2
      assertEquals(
          520,
          sql("SELECT * FROM enrollment AS a JOIN enrollment AS b ON a.student_id = b.student_id")
              .rows()
              .size(),
          protocol + ": self join enrollment");
      assertEquals(
          8000,
          sql("SELECT * FROM enrollment, course").rows().size(),
          protocol + ": enrollment x course");
    }
  }

  // ORDER BY (QP-09)

  @Test
  @DisplayName("ORDER BY ASC: angka naik berdasarkan nilai, NULL di awal")
  void orderByAscending() {
    List<Value> gpa = column(sql("SELECT student_id, gpa FROM student ORDER BY gpa ASC"), 1);

    assertEquals(50, gpa.size());
    assertNullsFirstThenNonDecreasing(gpa);
  }

  @Test
  @DisplayName("ORDER BY DESC: angka turun berdasarkan nilai, NULL di akhir")
  void orderByDescending() {
    List<Value> gpa = column(sql("SELECT student_id, gpa FROM student ORDER BY gpa DESC"), 1);

    assertEquals(50, gpa.size());
    List<Value> reversed = new ArrayList<>(gpa);
    java.util.Collections.reverse(reversed);
    assertNullsFirstThenNonDecreasing(reversed);
  }

  @Test
  @DisplayName("ORDER BY tanpa ASC/DESC sama dengan ASC")
  void orderByDefaultIsAscending() {
    List<Value> byDefault = column(sql("SELECT student_id, gpa FROM student ORDER BY gpa"), 1);
    List<Value> ascending = column(sql("SELECT student_id, gpa FROM student ORDER BY gpa ASC"), 1);

    assertEquals(ascending, byDefault);
  }

  @Test
  @DisplayName("ORDER BY kolom teks mengurutkan secara alfabetis")
  void orderByTextIsAlphabetical() {
    List<Value> names = column(sql("SELECT student_id, name FROM student ORDER BY name"), 1);

    assertEquals(50, names.size());
    for (int i = 1; i < names.size(); i++) {
      String previous = text(names.get(i - 1));
      String current = text(names.get(i));
      assertTrue(previous.compareTo(current) <= 0, previous + " harus sebelum " + current);
    }
  }

  @Test
  @DisplayName("ORDER BY setelah WHERE: hanya baris yang lolos, terurut")
  void orderByAfterWhere() {
    QueryResult result =
        sql(
            "SELECT student_id, gpa FROM student WHERE gpa >= 3.5"
                + " ORDER BY gpa DESC, student_id ASC");

    assertTrue(!result.rows().isEmpty(), "data contoh punya gpa >= 3.5");
    for (int i = 0; i < result.rows().size(); i++) {
      Row row = result.rows().get(i);
      assertTrue(number(row.values().get(1)) >= 3.5);
      if (i > 0) {
        Row previous = result.rows().get(i - 1);
        double previousGpa = number(previous.values().get(1));
        double currentGpa = number(row.values().get(1));
        assertTrue(previousGpa >= currentGpa, "gpa harus turun");
        if (previousGpa == currentGpa) {
          assertTrue(intAt(previous, 0) < intAt(row, 0), "student_id naik bila gpa sama");
        }
      }
    }
  }

  @Test
  @DisplayName("ORDER BY di atas join dengan WHERE dan kolom ber-prefix")
  void orderByOnJoinResult() {
    QueryResult result =
        sql(
            "SELECT student.student_id, enrollment.course_id FROM student JOIN enrollment"
                + " ON student.student_id = enrollment.student_id WHERE student.student_id <= 3"
                + " ORDER BY student.student_id DESC, enrollment.course_id ASC");

    // student s mengambil course s dan s + 25 (rumus SampleDatabaseLoader)
    List<String> actual = new ArrayList<>();
    for (Row row : result.rows()) {
      actual.add(intAt(row, 0) + "-" + intAt(row, 1));
    }
    assertEquals(List.of("3-3", "3-28", "2-2", "2-27", "1-1", "1-26"), actual);
  }

  @Test
  @DisplayName("ORDER BY pada hasil kosong tidak menimbulkan error")
  void orderByOnEmptyResult() {
    QueryResult result = sql("SELECT * FROM student WHERE student_id > 1000 ORDER BY gpa");

    assertEquals(0, result.rows().size());
    assertEquals(4, result.schema().columns().size());
  }

  // LIMIT + ORDER BY + JOIN
  @Test
  @DisplayName("ORDER BY gpa DESC LIMIT 5 mengambil lima gpa tertinggi (4.0)")
  void limitAfterDescendingSortTakesHighestGpa() {
    QueryResult result = sql("SELECT student_id, gpa FROM student ORDER BY gpa DESC LIMIT 5");

    Set<Integer> expected = new HashSet<>();
    for (int i = 1; i <= 50; i++) {
      if (sampleGpa(i) != null && sampleGpa(i) == 4.0) {
        expected.add(i);
      }
    }
    assertEquals(5, result.rows().size());
    Set<Integer> actual = new HashSet<>();
    for (Row row : result.rows()) {
      actual.add(intAt(row, 0));
      assertEquals(4.0, number(row.values().get(1)));
    }
    assertEquals(expected, actual);
  }

  @Test
  @DisplayName("ORDER BY gpa ASC LIMIT 5 dan ORDER BY tanpa arah LIMIT 5 mengambil kelima NULL")
  void limitAfterAscendingSortTakesNullsFirst() {
    QueryResult ascending = sql("SELECT student_id, gpa FROM student ORDER BY gpa ASC LIMIT 5");
    QueryResult byDefault = sql("SELECT student_id, gpa FROM student ORDER BY gpa LIMIT 5");

    Set<Integer> nullGpa = Set.of(10, 20, 30, 40, 50);
    for (QueryResult result : List.of(ascending, byDefault)) {
      assertEquals(5, result.rows().size());
      Set<Integer> ids = new HashSet<>();
      for (Row row : result.rows()) {
        ids.add(intAt(row, 0));
        assertTrue(row.values().get(1) instanceof NullValue, "NULL harus lebih dulu: " + row);
      }
      assertEquals(nullGpa, ids);
    }
  }

  @Test
  @DisplayName("ORDER BY name LIMIT 3 mengikuti urutan leksikografis teks")
  void limitAfterTextSort() {
    QueryResult result = sql("SELECT student_id, name FROM student ORDER BY name LIMIT 3");

    List<String> names = new ArrayList<>();
    for (int i = 1; i <= 50; i++) {
      names.add("Student " + i);
    }
    java.util.Collections.sort(names);
    assertEquals(
        names.subList(0, 3), column(result, 1).stream().map(QP4IntegrationTest::text).toList());
  }

  @Test
  @DisplayName("Conditional Join + ORDER BY DESC, ASC + LIMIT 3")
  void limitAfterConditionalJoinWithMixedDirections() {
    QueryResult result =
        sql(
            "SELECT student.student_id, enrollment.course_id FROM student JOIN enrollment"
                + " ON student.student_id = enrollment.student_id"
                + " ORDER BY student.student_id DESC, enrollment.course_id ASC LIMIT 3");

    List<String> expected =
        sampleEnrollments().stream()
            .sorted(
                java.util.Comparator.<int[]>comparingInt(e -> -e[0]).thenComparingInt(e -> e[1]))
            .limit(3)
            .map(e -> e[0] + "-" + e[1])
            .toList();
    assertEquals(List.of("50-25", "50-50", "49-24"), expected);
    assertEquals(expected, pairs(result, 0, 1));
  }

  @Test
  @DisplayName("Natural Join + ORDER BY ASC + LIMIT 5")
  void limitAfterNaturalJoinAscending() {
    QueryResult result =
        sql(
            "SELECT student_id, course_id, name FROM student NATURAL JOIN enrollment"
                + " ORDER BY student_id ASC, course_id ASC LIMIT 5");

    List<String> expected =
        sampleEnrollments().stream()
            .sorted(java.util.Comparator.<int[]>comparingInt(e -> e[0]).thenComparingInt(e -> e[1]))
            .limit(5)
            .map(e -> e[0] + "-" + e[1])
            .toList();
    assertEquals(3, result.schema().columns().size());
    assertEquals(expected, pairs(result, 0, 1));
    for (Row row : result.rows()) {
      assertEquals("Student " + intAt(row, 0), text(row.values().get(2)));
    }
  }

  @Test
  @DisplayName("Join tiga tabel + ORDER BY DESC + LIMIT 4")
  void limitAfterThreeTableJoinDescending() {
    QueryResult result =
        sql(
            "SELECT student.student_id, course.course_id, course.credits FROM student"
                + " JOIN enrollment ON student.student_id = enrollment.student_id"
                + " JOIN course ON enrollment.course_id = course.course_id"
                + " ORDER BY course.course_id DESC, student.student_id DESC LIMIT 4");

    List<String> expected =
        sampleEnrollments().stream()
            .sorted(
                java.util.Comparator.<int[]>comparingInt(e -> -e[1]).thenComparingInt(e -> -e[0]))
            .limit(4)
            .map(e -> e[0] + "-" + e[1])
            .toList();
    assertEquals(expected, pairs(result, 0, 1));
    for (Row row : result.rows()) {
      assertEquals(2 + intAt(row, 1) % 3, intAt(row, 2), "credits = 2 + course_id % 3");
    }
  }

  @Test
  @DisplayName("Join + WHERE + ORDER BY DESC, ASC + LIMIT 4")
  void limitAfterJoinWhereAndSort() {
    QueryResult result =
        sql(
            "SELECT student.student_id, enrollment.course_id, student.gpa FROM student"
                + " JOIN enrollment ON student.student_id = enrollment.student_id"
                + " WHERE student.gpa >= 3.5"
                + " ORDER BY student.gpa DESC, enrollment.course_id ASC LIMIT 4");

    List<String> expected =
        sampleEnrollments().stream()
            .filter(e -> sampleGpa(e[0]) != null && sampleGpa(e[0]) >= 3.5)
            .sorted(
                java.util.Comparator.<int[]>comparingDouble(e -> -sampleGpa(e[0]))
                    .thenComparingInt(e -> e[1]))
            .limit(4)
            .map(e -> e[0] + "-" + e[1])
            .toList();
    assertEquals(expected, pairs(result, 0, 1));
    for (Row row : result.rows()) {
      assertTrue(number(row.values().get(2)) >= 3.5);
    }
  }

  @Test
  @DisplayName("LIMIT 0, LIMIT melebihi jumlah baris, dan LIMIT pada hasil kosong di atas join")
  void limitBoundariesOnJoin() {
    String join =
        "SELECT student.student_id, enrollment.course_id FROM student JOIN enrollment"
            + " ON student.student_id = enrollment.student_id";

    QueryResult zero = sql(join + " ORDER BY student.student_id LIMIT 0");
    assertEquals(0, zero.rows().size());
    assertEquals(2, zero.schema().columns().size());

    QueryResult all = sql(join + " ORDER BY student.student_id DESC, enrollment.course_id DESC");
    QueryResult larger =
        sql(join + " ORDER BY student.student_id DESC, enrollment.course_id DESC LIMIT 1000");
    assertEquals(100, larger.rows().size());
    assertEquals(all.rows(), larger.rows());

    QueryResult empty =
        sql(join + " WHERE student.student_id > 1000 ORDER BY student.student_id LIMIT 5");
    assertEquals(0, empty.rows().size());
    assertEquals(2, empty.schema().columns().size());
  }

  @Test
  @DisplayName("LIMIT negatif atau bukan angka ditolak sebagai SQL_SYNTAX")
  void invalidLimitIsRejected() {
    for (String limit : List.of("-1", "abc", "1.5", "")) {
      DbmsException error =
          assertThrows(
              DbmsException.class,
              () -> sql("SELECT student_id FROM student ORDER BY gpa DESC LIMIT " + limit),
              "LIMIT " + limit);
      assertEquals(ErrorCode.SQL_SYNTAX, error.getCode(), "LIMIT " + limit);
    }
  }

  @Test
  @DisplayName("COUNT(*), COUNT(gpa), SUM, AVG, MIN, MAX pada student.gpa (NULL diabaikan)")
  void aggregatesOnStudentGpa() {
    Row row =
        single(
            sql(
                "SELECT COUNT(*), COUNT(gpa), SUM(gpa), AVG(gpa), MIN(gpa), MAX(gpa)"
                    + " FROM student"));

    int count = 0;
    double sum = 0;
    double min = Double.MAX_VALUE;
    double max = -Double.MAX_VALUE;
    for (int i = 1; i <= 50; i++) {
      Double gpa = sampleGpa(i);
      if (gpa != null) {
        count++;
        sum += gpa;
        min = Math.min(min, gpa);
        max = Math.max(max, gpa);
      }
    }
    assertEquals(new IntValue(50), row.values().get(0));
    assertEquals(new IntValue(count), row.values().get(1)); // 45
    assertEquals(sum, number(row.values().get(2)), 1e-9); // 135.0
    assertEquals(sum / count, number(row.values().get(3)), 1e-9); // 3.0
    assertEquals(min, number(row.values().get(4)));
    assertEquals(max, number(row.values().get(5)));
  }

  @Test
  @DisplayName("Agregat di atas Conditional Join dan Natural Join")
  void aggregatesOverJoin() {
    Row conditional =
        single(
            sql(
                "SELECT COUNT(*), COUNT(enrollment.score), SUM(enrollment.score),"
                    + " MIN(enrollment.score), MAX(student.gpa) FROM student JOIN enrollment"
                    + " ON student.student_id = enrollment.student_id"));
    Row natural = single(sql("SELECT COUNT(*), COUNT(score) FROM student NATURAL JOIN enrollment"));

    int count = 0;
    double sum = 0;
    double min = Double.MAX_VALUE;
    for (int i = 0; i < 100; i++) {
      Double score = sampleScore(i);
      if (score != null) {
        count++;
        sum += score;
        min = Math.min(min, score);
      }
    }
    assertEquals(new IntValue(100), conditional.values().get(0));
    assertEquals(new IntValue(count), conditional.values().get(1)); // 88
    assertEquals(sum, number(conditional.values().get(2)), 1e-9); // 6810.0
    assertEquals(min, number(conditional.values().get(3))); // 60.5
    assertEquals(4.0, number(conditional.values().get(4)));
    assertEquals(List.of(new IntValue(100), new IntValue(count)), natural.values());
  }

  @Test
  @DisplayName("GROUP BY + HAVING + ORDER BY DESC + LIMIT, termasuk agregat di atas Natural Join")
  void groupedAggregatesWithSortAndLimit() {
    QueryResult byGrade =
        sql(
            "SELECT grade, COUNT(*) AS n, COUNT(gpa) AS ng FROM student"
                + " GROUP BY grade HAVING COUNT(*) > 12 ORDER BY grade DESC LIMIT 1");
    int n = 0;
    int ng = 0;
    for (int i = 1; i <= 50; i++) {
      if (i % 4 == 2) {
        n++;
        if (sampleGpa(i) != null) {
          ng++;
        }
      }
    }
    assertEquals(1, byGrade.rows().size());
    Row grade = byGrade.rows().get(0);
    assertEquals("C", text(grade.values().get(0)));
    assertEquals(List.of(new IntValue(n), new IntValue(ng)), grade.values().subList(1, 3));

    QueryResult perStudent =
        sql(
            "SELECT student_id, COUNT(*) AS n FROM student NATURAL JOIN enrollment"
                + " GROUP BY student_id ORDER BY student_id DESC LIMIT 3");
    List<String> expected = new ArrayList<>();
    for (int student = 50; student > 47; student--) {
      int s = student;
      expected.add(s + "-" + sampleEnrollments().stream().filter(e -> e[0] == s).count());
    }
    assertEquals(expected, pairs(perStudent, 0, 1));
  }

  @Test
  @DisplayName("JOIN ... ON langsung diikuti GROUP BY dan HAVING, lalu ORDER BY dan LIMIT")
  void joinOnFollowedByGroupByAndHaving() {
    String join = " FROM student JOIN enrollment ON student.student_id = enrollment.student_id";

    QueryResult perStudent =
        sql(
            "SELECT student.student_id, COUNT(*) AS n"
                + join
                + " GROUP BY student.student_id HAVING COUNT(*) > 1"
                + " ORDER BY student.student_id DESC LIMIT 3");
    List<String> expected = new ArrayList<>();
    for (int student = 50; student > 47; student--) {
      int s = student;
      expected.add(s + "-" + sampleEnrollments().stream().filter(e -> e[0] == s).count());
    }
    assertEquals(expected, pairs(perStudent, 0, 1));

    assertEquals(
        List.of(new IntValue(100)),
        single(sql("SELECT COUNT(*)" + join + " HAVING COUNT(*) > 1")).values());

    QueryResult byGrade =
        sql(
            "SELECT student.grade, SUM(enrollment.course_id) AS total"
                + join
                + " GROUP BY student.grade ORDER BY student.grade");
    int[] totals = new int[4];
    for (int[] e : sampleEnrollments()) {
      totals[e[0] % 4] += e[1];
    }
    List<String> expectedTotals = new ArrayList<>();
    for (int g = 0; g < 4; g++) {
      expectedTotals.add((char) ('A' + g) + "-" + totals[g]);
    }
    List<String> actualTotals = new ArrayList<>();
    for (Row row : byGrade.rows()) {
      actualTotals.add(text(row.values().get(0)) + "-" + intAt(row, 1));
    }
    assertEquals(expectedTotals, actualTotals);
  }

  @Test
  @DisplayName(
      "Agregat atas WHERE yang tidak cocok: 1 baris (COUNT 0, lainnya NULL); GROUP BY 0 baris")
  void aggregatesOnEmptyFilteredInput() {
    Row global =
        single(
            sql(
                "SELECT COUNT(*), COUNT(gpa), SUM(gpa), AVG(gpa), MIN(gpa), MAX(gpa) FROM student"
                    + " WHERE student_id > 1000"));
    assertEquals(new IntValue(0), global.values().get(0));
    assertEquals(new IntValue(0), global.values().get(1));
    for (int i = 2; i < 6; i++) {
      assertTrue(global.values().get(i) instanceof NullValue, "kolom ke-" + i);
    }

    Row overJoin =
        single(
            sql(
                "SELECT COUNT(*), SUM(enrollment.score) FROM student JOIN enrollment"
                    + " ON student.student_id = enrollment.student_id"
                    + " WHERE student.student_id > 1000"));
    assertEquals(List.of(new IntValue(0), NullValue.INSTANCE), overJoin.values());

    assertEquals(
        0,
        sql("SELECT grade, COUNT(*) FROM student WHERE student_id > 1000 GROUP BY grade")
            .rows()
            .size());
  }

  @Test
  @DisplayName("Kolom yang seluruhnya NULL: COUNT(kolom) 0, SUM/AVG/MIN/MAX NULL")
  void aggregatesOnAllNullColumn() {
    sql("CREATE TABLE qp4_nulls (id INTEGER PRIMARY KEY, val INTEGER, f FLOAT)");
    for (int id = 1; id <= 3; id++) {
      assertEquals(1, sql("INSERT INTO qp4_nulls VALUES (" + id + ", NULL, NULL)").affectedRows());
    }

    Row row =
        single(
            sql(
                "SELECT COUNT(*), COUNT(val), SUM(val), AVG(val), MIN(val), MAX(val), SUM(f)"
                    + " FROM qp4_nulls"));
    assertEquals(new IntValue(3), row.values().get(0));
    assertEquals(new IntValue(0), row.values().get(1));
    for (int i = 2; i < 7; i++) {
      assertTrue(row.values().get(i) instanceof NullValue, "kolom ke-" + i);
    }
  }

  @Test
  @DisplayName(
      "SUM INTEGER: 2147483647 dua kali overflow (INVALID_STATE), batas tepat tidak overflow")
  void integerSumOverflowAndExactBoundary() {
    sql("CREATE TABLE qp4_sum (id INTEGER PRIMARY KEY, val INTEGER)");
    sql("INSERT INTO qp4_sum VALUES (1, 2147483647)");
    sql("INSERT INTO qp4_sum VALUES (2, 2147483647)");

    DbmsException overflow =
        assertThrows(DbmsException.class, () -> sql("SELECT SUM(val) FROM qp4_sum"));
    assertEquals(ErrorCode.INVALID_STATE, overflow.getCode());
    assertEquals(
        (double) Integer.MAX_VALUE,
        number(single(sql("SELECT AVG(val) FROM qp4_sum")).values().get(0)));

    sql("INSERT INTO qp4_sum VALUES (3, 2147483640)");
    sql("INSERT INTO qp4_sum VALUES (4, 7)");
    assertEquals(
        new IntValue(Integer.MAX_VALUE),
        single(sql("SELECT SUM(val) FROM qp4_sum WHERE id >= 3")).values().get(0));

    sql("INSERT INTO qp4_sum VALUES (5, -2147483647)");
    sql("INSERT INTO qp4_sum VALUES (6, -1)");
    assertEquals(
        new IntValue(Integer.MIN_VALUE),
        single(sql("SELECT SUM(val) FROM qp4_sum WHERE id >= 5")).values().get(0));
  }

  @Test
  @DisplayName("SUM INTEGER boleh melewati batas di tengah penjumlahan bila total akhirnya muat")
  void integerSumMayLeaveTheRangeWhileAccumulating() {
    sql("CREATE TABLE qp4_running (id INTEGER PRIMARY KEY, val INTEGER)");
    sql("INSERT INTO qp4_running VALUES (1, 2147483647)");
    sql("INSERT INTO qp4_running VALUES (2, 7)");
    sql("INSERT INTO qp4_running VALUES (3, -2147483647)");
    sql("INSERT INTO qp4_running VALUES (4, 2147483647)");

    assertEquals(
        new IntValue(7),
        single(sql("SELECT SUM(val) FROM qp4_running WHERE id <= 3")).values().get(0));
    assertEquals(
        new IntValue(Integer.MAX_VALUE),
        single(sql("SELECT SUM(val) FROM qp4_running WHERE id = 1 OR id = 3 OR id = 4"))
            .values()
            .get(0));

    DbmsException overflow =
        assertThrows(DbmsException.class, () -> sql("SELECT SUM(val) FROM qp4_running"));
    assertEquals(ErrorCode.INVALID_STATE, overflow.getCode());
  }

  // Helper
  private static List<int[]> sampleEnrollments() {
    List<int[]> rows = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      rows.add(new int[] {i % 50 + 1, (i % 50 + (i / 50) * 25) % 50 + 1});
    }
    return rows;
  }

  private static Double sampleGpa(int i) {
    return i % 10 == 0 ? null : 2.0 + (i % 9) * 0.25;
  }

  private static Double sampleScore(int i) {
    return i % 9 == 0 ? null : 60.0 + (i % 81) * 0.5;
  }

  private static List<String> pairs(QueryResult result, int first, int second) {
    List<String> pairs = new ArrayList<>();
    for (Row row : result.rows()) {
      pairs.add(intAt(row, first) + "-" + intAt(row, second));
    }
    return pairs;
  }

  private static Row single(QueryResult result) {
    assertEquals(1, result.rows().size(), "agregat tanpa GROUP BY harus menghasilkan 1 baris");
    return result.rows().get(0);
  }

  private void start(ProtocolType protocol, String dataDirectory) throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory.resolve(dataDirectory), protocol);
    runtime = new StubRuntime(config);
    runtime.bootstrap().start(config);
    new SampleDatabaseLoader(runtime.storage(), runtime.concurrency(), runtime.concurrency())
        .loadIfAbsent(runtime.catalog());
    session = runtime.processor().openSession("qp4-integration");
  }

  private QueryResult sql(String query) {
    return runtime.processor().execute(session, query);
  }

  private static int indexOf(QueryResult result, String relation, String name) {
    List<Column> columns = result.schema().columns();
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      if (relation.equalsIgnoreCase(column.relation()) && name.equalsIgnoreCase(column.name())) {
        return i;
      }
    }
    throw new AssertionError("kolom " + relation + "." + name + " tidak ada di " + columns);
  }

  private static long countColumnsNamed(QueryResult result, String name) {
    return result.schema().columns().stream().filter(c -> name.equalsIgnoreCase(c.name())).count();
  }

  private static List<Value> column(QueryResult result, int index) {
    List<Value> values = new ArrayList<>();
    for (Row row : result.rows()) {
      values.add(row.values().get(index));
    }
    return values;
  }

  private static int intAt(Row row, int index) {
    return ((IntValue) row.values().get(index)).value();
  }

  private static double number(Value value) {
    if (value instanceof IntValue i) {
      return i.value();
    }
    if (value instanceof FloatValue f) {
      return f.value();
    }
    throw new AssertionError("bukan angka: " + value);
  }

  private static String text(Value value) {
    return ((TextValue) value).value().stripTrailing();
  }

  private static void assertNullsFirstThenNonDecreasing(List<Value> values) {
    int i = 0;
    while (i < values.size() && values.get(i) instanceof NullValue) {
      i++;
    }
    assertEquals(5, i, "student 10, 20, 30, 40, 50 memiliki gpa NULL");
    for (int j = i + 1; j < values.size(); j++) {
      assertTrue(!(values.get(j) instanceof NullValue), "NULL hanya boleh di awal");
      assertTrue(
          number(values.get(j - 1)) <= number(values.get(j)),
          "urutan salah di posisi " + j + ": " + values.get(j - 1) + " > " + values.get(j));
    }
  }
}
