package dynamitedb.qp.operator;

import static dynamitedb.qp.operator.TestRows.col;
import static dynamitedb.qp.operator.TestRows.column;
import static dynamitedb.qp.operator.TestRows.drain;
import static dynamitedb.qp.operator.TestRows.list;
import static dynamitedb.qp.operator.TestRows.r;
import static dynamitedb.qp.operator.TestRows.schema;
import static dynamitedb.qp.operator.TestRows.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.storage.Schema;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SortOperatorTest {

  private static final Schema STUDENTS = schema("students", "name", "gpa");

  private static SortOperator sortBy(Operator child, String column, SortDirection dir) {
    return new SortOperator(child, List.of(new SortKey(col(column), dir)));
  }

  @Test
  @DisplayName("ORDER BY ASC mengurutkan naik")
  void ascendingOrder() {
    // Arrange
    ListOperator child = table(STUDENTS, r("A", 3.5), r("B", 3.9), r("C", 3.2));
    // Act
    List<OperatorRow> out = drain(sortBy(child, "gpa", SortDirection.ASC));
    // Assert
    assertEquals(list(3.2, 3.5, 3.9), column(out, 1));
  }

  @Test
  @DisplayName("ORDER BY DESC mengurutkan turun")
  void descendingOrder() {
    ListOperator child = table(STUDENTS, r("A", 3.5), r("B", 3.9), r("C", 3.2));
    List<OperatorRow> out = drain(sortBy(child, "gpa", SortDirection.DESC));
    assertEquals(list(3.9, 3.5, 3.2), column(out, 1));
  }

  @Test
  @DisplayName("Lebih dari satu kunci: kunci kedua dipakai saat kunci pertama seri")
  void multipleKeys() {
    ListOperator child =
        table(STUDENTS, r("Citra", 3.5), r("Andi", 3.9), r("Budi", 3.5), r("Dewi", 3.2));
    SortOperator sort =
        new SortOperator(
            child,
            List.of(
                new SortKey(col("gpa"), SortDirection.DESC),
                new SortKey(col("name"), SortDirection.ASC)));

    List<OperatorRow> out = drain(sort);

    assertEquals(list("Andi", "Budi", "Citra", "Dewi"), column(out, 0));
  }

  @Test
  @DisplayName("Angka diurutkan berdasarkan nilai: 2 sebelum 10, INTEGER dan FLOAT bisa dicampur")
  void numbersSortedByValue() {
    Schema s = schema("t", "x");
    ListOperator child = table(s, r(10), r(2), r(33), r(2.5));
    List<OperatorRow> out = drain(sortBy(child, "x", SortDirection.ASC));
    assertEquals(list(2, 2.5, 10, 33), column(out, 0));
  }

  @Test
  @DisplayName("Teks diurutkan alfabetis per karakter, termasuk karakter khusus")
  void textSortedAlphabetically() {
    Schema s = schema("t", "name");
    ListOperator child = table(s, r("budi"), r("Andi"), r("_x"), r("andi"));
    List<OperatorRow> out = drain(sortBy(child, "name", SortDirection.ASC));
    assertEquals(list("Andi", "_x", "andi", "budi"), column(out, 0));
  }

  @Test
  @DisplayName("NULL muncul paling awal pada ASC dan paling akhir pada DESC")
  void nullsSortFirstOnAsc() {
    ListOperator asc = table(STUDENTS, r("A", 3.5), r("B", null), r("C", 3.2));
    ListOperator desc = table(STUDENTS, r("A", 3.5), r("B", null), r("C", 3.2));

    assertEquals(list("B", "C", "A"), column(drain(sortBy(asc, "gpa", SortDirection.ASC)), 0));
    assertEquals(list("A", "C", "B"), column(drain(sortBy(desc, "gpa", SortDirection.DESC)), 0));
  }

  @Test
  @DisplayName("Hasil kosong tidak menimbulkan error")
  void emptyInput() {
    ListOperator child = table(STUDENTS);
    List<OperatorRow> out = drain(sortBy(child, "gpa", SortDirection.ASC));
    assertTrue(out.isEmpty());
  }

  @Test
  @DisplayName("Nilai kunci sama mempertahankan urutan asli (stable sort)")
  void stableForEqualKeys() {
    ListOperator child = table(STUDENTS, r("A", 3.5), r("B", 3.5), r("C", 3.0));
    List<OperatorRow> out = drain(sortBy(child, "gpa", SortDirection.ASC));
    assertEquals(list("C", "A", "B"), column(out, 0));
  }

  @Test
  @DisplayName("open() ulang menghasilkan urutan yang sama dari awal")
  void reopenStartsOver() {
    SortOperator sort = sortBy(table(STUDENTS, r("A", 2.0), r("B", 1.0)), "gpa", SortDirection.ASC);
    assertEquals(list("B", "A"), column(drain(sort), 0));
    assertEquals(list("B", "A"), column(drain(sort), 0));
  }

  @Test
  @DisplayName("Sort bisa dipakai di atas hasil join, dengan prefix tabel")
  void sortOnJoinResult() {
    Schema s = schema("s", "id", "name");
    Schema t = schema("t", "id", "course");
    BlockNestedLoopJoinOperator join =
        new BlockNestedLoopJoinOperator(
            table(s, r(2, "Budi"), r(1, "Andi")),
            table(t, r(9, "SBD")),
            JoinKind.CARTESIAN,
            Optional.empty(),
            null);
    SortOperator sort =
        new SortOperator(join, List.of(new SortKey(col("s", "id"), SortDirection.ASC)));
    assertEquals(list("Andi", "Budi"), column(drain(sort), 1));
  }

  @Test
  @DisplayName("Kolom yang tidak ada menghasilkan error yang jelas")
  void unknownColumnThrows() {
    SortOperator sort = sortBy(table(STUDENTS, r("A", 1.0)), "salary", SortDirection.ASC);
    sort.open();
    assertThrows(DbmsException.class, sort::next);
  }

  @Test
  @DisplayName("next() sebelum open() menghasilkan error")
  void nextBeforeOpenThrows() {
    SortOperator sort = sortBy(table(STUDENTS), "gpa", SortDirection.ASC);
    assertThrows(DbmsException.class, sort::next);
  }

  @Test
  @DisplayName("open() ulang saat masih terbuka menutup anak lebih dulu")
  void reopenWhileOpenClosesChildFirst() {
    ListOperator child = table(STUDENTS, r("A", 1.0));
    SortOperator sort = sortBy(child, "gpa", SortDirection.ASC);
    sort.open();
    sort.open();
    assertEquals(2, child.openCount);
    assertEquals(1, child.closeCount);
    sort.close();
    sort.close();
    assertEquals(2, child.closeCount);
  }
}
