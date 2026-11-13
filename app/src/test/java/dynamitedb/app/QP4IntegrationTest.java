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

  // Helper

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
