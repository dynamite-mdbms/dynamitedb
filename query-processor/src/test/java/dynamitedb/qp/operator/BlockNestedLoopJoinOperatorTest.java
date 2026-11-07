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
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockNestedLoopJoinOperatorTest {

  private static final Schema STUDENTS = schema("students", "nim", "name");
  private static final Schema TAKES = schema("takes", "nim", "course");

  private static final Optional<Expression> NIM_EQUALS =
      Optional.of(
          new BinaryExpression(
              new ColumnExpression(col("students", "nim")),
              BinaryOp.EQ,
              new ColumnExpression(col("takes", "nim"))));

  private static BlockNestedLoopJoinOperator join(Operator l, Operator r, JoinKind kind) {
    Optional<Expression> cond = kind == JoinKind.THETA ? NIM_EQUALS : Optional.empty();
    ExpressionEvaluator ev = kind == JoinKind.THETA ? new ExpressionEvaluator() : null;
    return new BlockNestedLoopJoinOperator(l, r, kind, cond, ev);
  }

  // CARTESIAN

  @Test
  @DisplayName("CARTESIAN menghasilkan seluruh kombinasi pasangan (2 x 3 = 6)")
  void cartesianProducesAllPairs() {
    // Arrange
    ListOperator left = table(STUDENTS, r(1, "Andi"), r(2, "Budi"));
    ListOperator right = table(TAKES, r(1, "SBD"), r(1, "AI"), r(3, "IMK"));
    // Act
    List<OperatorRow> out = drain(join(left, right, JoinKind.CARTESIAN));
    // Assert
    assertEquals(6, out.size());
    assertEquals(4, out.get(0).row().values().size()); // 2 kolom kiri + 2 kolom kanan
  }

  @Test
  @DisplayName("Anak kanan di-rescan satu kali per blok (inti BNLJ)")
  void rightIsRescannedOncePerBlock() {
    // Arrange: 250 baris kiri dengan blok 100 baris = 3 blok (100 + 100 + 50)
    List<OperatorRow> leftRows = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      leftRows.add(TestRows.row(STUDENTS, i, "S" + i));
    }

    ListOperator left = new ListOperator(leftRows);
    ListOperator right = table(TAKES, r(1, "SBD"), r(2, "AI"));

    // Act
    List<OperatorRow> out = drain(join(left, right, JoinKind.CARTESIAN));

    // Assert
    assertEquals(500, out.size()); // 250 x 2
    assertEquals(3, right.openCount);
    assertEquals(1, left.openCount); // relasi luar hanya dibaca sekali
  }

  // THETA

  @Test
  @DisplayName("THETA hanya mengeluarkan pasangan yang memenuhi kondisi ON")
  void thetaFiltersByCondition() {
    ListOperator left = table(STUDENTS, r(1, "Andi"), r(2, "Budi"));
    ListOperator right = table(TAKES, r(1, "SBD"), r(1, "AI"), r(3, "IMK"));

    List<OperatorRow> out = drain(join(left, right, JoinKind.THETA));

    assertEquals(2, out.size());
    assertEquals(list("Andi", "Andi"), column(out, 1));
    assertEquals(list("SBD", "AI"), column(out, 3));
  }

  @Test
  @DisplayName("THETA tanpa kondisi ditolak saat konstruksi")
  void thetaWithoutConditionRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlockNestedLoopJoinOperator(
                table(STUDENTS),
                table(TAKES),
                JoinKind.THETA,
                Optional.empty(),
                new ExpressionEvaluator()));
  }

  // NATURAL

  @Test
  @DisplayName("NATURAL menggabungkan baris dengan nilai identik pada kolom bernama sama")
  void naturalMatchesOnCommonColumns() {
    ListOperator left = table(STUDENTS, r(1, "Andi"), r(2, "Budi"));
    ListOperator right = table(TAKES, r(1, "SBD"), r(1, "AI"), r(3, "IMK"));

    List<OperatorRow> out = drain(join(left, right, JoinKind.NATURAL));

    assertEquals(2, out.size());
    assertEquals(list("SBD", "AI"), column(out, 2));
  }

  @Test
  @DisplayName("NATURAL mengeliminasi kolom duplikat dari hasil")
  void naturalDropsDuplicateColumn() {
    List<OperatorRow> out =
        drain(join(table(STUDENTS, r(1, "Andi")), table(TAKES, r(1, "SBD")), JoinKind.NATURAL));

    OperatorRow row = out.get(0);
    assertEquals(3, row.row().values().size()); // nim, name, course
    assertEquals(3, row.schema().columns().size());
    assertEquals("course", row.schema().columns().get(2).name());
  }

  @Test
  @DisplayName("NATURAL tanpa kolom bersama berperilaku seperti CARTESIAN")
  void naturalWithoutCommonColumnsIsCartesian() {
    Schema a = schema("a", "x");
    Schema b = schema("b", "y");
    List<OperatorRow> out =
        drain(join(table(a, r(1), r(2)), table(b, r(3), r(4)), JoinKind.NATURAL));
    assertEquals(4, out.size());
  }

  @Test
  @DisplayName("NATURAL: NULL tidak pernah cocok, bahkan dengan NULL")
  void naturalNeverMatchesNull() {
    ListOperator left = table(STUDENTS, r(null, "Andi"), r(1, "Budi"));
    ListOperator right = table(TAKES, r(null, "SBD"), r(1, "AI"));

    List<OperatorRow> out = drain(join(left, right, JoinKind.NATURAL));

    assertEquals(1, out.size());
    assertEquals(list("Budi"), column(out, 1));
  }

  @Test
  @DisplayName("NATURAL: teks yang hanya beda spasi di belakang (padding CHAR) dianggap sama")
  void naturalIgnoresTrailingSpaces() {
    Schema a = schema("a", "name", "x");
    Schema b = schema("b", "name", "y");

    List<OperatorRow> out =
        drain(join(table(a, r("Andi  ", 1)), table(b, r("Andi", 2)), JoinKind.NATURAL));

    assertEquals(1, out.size());
  }

  // helper

  @Test
  @DisplayName("Join dengan tabel kosong menghasilkan 0 baris tanpa error")
  void emptySideProducesNoRows() {
    assertTrue(
        drain(join(table(STUDENTS), table(TAKES, r(1, "SBD")), JoinKind.CARTESIAN)).isEmpty());
    assertTrue(drain(join(table(STUDENTS, r(1, "A")), table(TAKES), JoinKind.CARTESIAN)).isEmpty());
  }

  @Test
  @DisplayName("Tabel kiri kosong: anak kanan tidak pernah dibaca")
  void emptyLeftNeverOpensRight() {
    ListOperator right = table(TAKES, r(1, "SBD"));
    drain(join(table(STUDENTS), right, JoinKind.CARTESIAN));
    assertEquals(0, right.openCount);
  }

  @Test
  @DisplayName("Mendukung join lebih dari dua tabel (join bertingkat)")
  void multiTableJoin() {
    Schema courses = schema("courses", "course", "sks");
    BlockNestedLoopJoinOperator st =
        join(
            table(STUDENTS, r(1, "Andi"), r(2, "Budi")),
            table(TAKES, r(1, "SBD"), r(2, "AI")),
            JoinKind.NATURAL);
    BlockNestedLoopJoinOperator stc =
        join(st, table(courses, r("SBD", 3), r("AI", 4)), JoinKind.NATURAL);

    List<OperatorRow> out = drain(stc);

    assertEquals(2, out.size());
    assertEquals(4, out.get(0).row().values().size()); // nim, name, course, sks
    assertEquals(list(3, 4), column(out, 3));
  }

  @Test
  @DisplayName("Kolom hasil join bisa diakses dengan prefix tabel (tabel.kolom)")
  void columnsAccessibleWithTablePrefix() {
    List<OperatorRow> out =
        drain(join(table(STUDENTS, r(1, "Andi")), table(TAKES, r(7, "SBD")), JoinKind.CARTESIAN));
    OperatorRow joined = out.get(0);

    assertEquals(0, joined.findColumnIndex(col("students", "nim")));
    assertEquals(2, joined.findColumnIndex(col("takes", "nim")));
    assertThrows(DbmsException.class, () -> joined.findColumnIndex(col("nim")));
  }

  @Test
  @DisplayName("close() menutup anak kiri dan kanan")
  void closeClosesChildren() {
    ListOperator left = table(STUDENTS, r(1, "A"));
    ListOperator right = table(TAKES, r(1, "SBD"));
    BlockNestedLoopJoinOperator op = join(left, right, JoinKind.CARTESIAN);
    op.open();
    op.next(); // anak kanan sudah terbuka
    op.close();
    assertTrue(left.closeCount >= 1);
    assertTrue(right.closeCount >= 1);
  }

  @Test
  @DisplayName("next() sebelum open() menghasilkan error")
  void nextBeforeOpenThrows() {
    BlockNestedLoopJoinOperator op = join(table(STUDENTS), table(TAKES), JoinKind.CARTESIAN);
    assertThrows(DbmsException.class, op::next);
  }
}
