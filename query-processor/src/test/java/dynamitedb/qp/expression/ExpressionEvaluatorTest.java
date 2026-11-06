package dynamitedb.qp.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExpressionEvaluatorTest {

  // baris hasil join employee e dengan department d, kolom id ada di dua tabel
  private static final OperatorRow ROW =
      new OperatorRow(
          new Row(
              List.of(
                  new IntValue(7), new FloatValue(4000.0), new TextValue("Ali"), new IntValue(3))),
          new Schema(
              List.of(
                  new Column("e", "id", DataType.INTEGER, 0, false),
                  new Column("e", "salary", DataType.FLOAT, 0, true),
                  new Column("e", "name", DataType.VARCHAR, 20, false),
                  new Column("d", "id", DataType.INTEGER, 0, false))),
          Map.of());

  private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

  // INT ketemu INT hasilnya tetap INT, 7 / 2 jadi 3 bukan 3.5
  @Test
  void integerArithmeticStaysInteger() {
    assertEquals(new IntValue(10), eval(bin(num(7), BinaryOp.ADD, num(3))));
    assertEquals(new IntValue(4), eval(bin(num(7), BinaryOp.SUB, num(3))));
    assertEquals(new IntValue(21), eval(bin(num(7), BinaryOp.MUL, num(3))));
    assertEquals(new IntValue(3), eval(bin(num(7), BinaryOp.DIV, num(2))));
  }

  // kalau salah satu FLOAT, hasilnya ikut jadi FLOAT
  @Test
  void mixedArithmeticIsPromotedToFloat() {
    assertEquals(new FloatValue(10.5), eval(bin(num(7), BinaryOp.MUL, lit(new FloatValue(1.5)))));
  }

  // bagi nol harus error, baik INT maupun FLOAT
  @Test
  void divisionByZeroIsRejected() {
    assertCode(ErrorCode.INVALID_STATE, bin(num(7), BinaryOp.DIV, num(0)));
    assertCode(ErrorCode.INVALID_STATE, bin(lit(new FloatValue(1.0)), BinaryOp.DIV, num(0)));
  }

  // ambil nilai kolom pakai nama tabel (e.salary) atau tanpa (NAME), huruf besar kecil bebas
  @Test
  void readsQualifiedAndUnqualifiedColumns() {
    assertEquals(new FloatValue(4000.0), eval(col("e", "salary")));
    assertEquals(new TextValue("Ali"), eval(col(null, "NAME")));
    assertEquals(new IntValue(3), eval(col("d", "id")));
  }

  // id ada di e dan d jadi ambigu, e.age ga ada di schema
  @Test
  void ambiguousOrMissingColumnIsRejected() {
    assertCode(ErrorCode.SQL_BIND, col(null, "id"));
    assertCode(ErrorCode.SQL_BIND, col("e", "age"));
  }

  // perbandingan kolom vs nilai dan kolom vs kolom
  @Test
  void comparesColumnWithValueAndWithColumn() {
    // contoh dari spek: salary * 1.05 > 4000
    Expression raised = bin(col("e", "salary"), BinaryOp.MUL, lit(new FloatValue(1.05)));
    assertEquals(new BooleanValue(true), eval(bin(raised, BinaryOp.GT, num(4000))));
    assertEquals(new BooleanValue(true), eval(bin(col("e", "id"), BinaryOp.GT, col("d", "id"))));
    assertEquals(new BooleanValue(false), eval(bin(col("e", "id"), BinaryOp.LTE, col("d", "id"))));
    assertEquals(new BooleanValue(true), eval(bin(num(4000), BinaryOp.EQ, col("e", "salary"))));
    assertEquals(new BooleanValue(true), eval(bin(num(1), BinaryOp.NEQ, num(2))));
  }

  // teks bedain huruf besar kecil, tapi spasi di belakang diabaikan
  @Test
  void comparesTextCaseSensitiveIgnoringTrailingSpaces() {
    assertEquals(new BooleanValue(true), eval(bin(col("e", "name"), BinaryOp.EQ, text("Ali  "))));
    assertEquals(new BooleanValue(false), eval(bin(col("e", "name"), BinaryOp.EQ, text("ali"))));
    assertEquals(new BooleanValue(true), eval(bin(text("Ali"), BinaryOp.LT, text("Budi"))));
  }

  // teks + angka, teks > angka, angka AND boolean semuanya ga valid
  @Test
  void mismatchedTypesAreRejected() {
    assertCode(ErrorCode.SQL_BIND, bin(text("Ali"), BinaryOp.ADD, num(1)));
    assertCode(ErrorCode.SQL_BIND, bin(text("Ali"), BinaryOp.GT, num(1)));
    assertCode(ErrorCode.SQL_BIND, bin(num(1), BinaryOp.AND, lit(new BooleanValue(true))));
  }

  // ada NULL di salah satu sisi, hasilnya NULL juga
  @Test
  void nullPropagatesThroughArithmeticAndComparison() {
    assertEquals(NullValue.INSTANCE, eval(bin(nul(), BinaryOp.ADD, num(1))));
    assertEquals(NullValue.INSTANCE, eval(bin(num(1), BinaryOp.EQ, nul())));
  }

  // FALSE menang lawan NULL, TRUE AND NULL tetap NULL
  @Test
  void andFollowsThreeValuedLogic() {
    assertEquals(new BooleanValue(false), eval(bin(bool(false), BinaryOp.AND, nul())));
    assertEquals(new BooleanValue(false), eval(bin(nul(), BinaryOp.AND, bool(false))));
    assertEquals(NullValue.INSTANCE, eval(bin(bool(true), BinaryOp.AND, nul())));
    assertEquals(new BooleanValue(true), eval(bin(bool(true), BinaryOp.AND, bool(true))));
  }

  // WHERE cuma lolos kalau TRUE, FALSE dan NULL dibuang
  @Test
  void predicatePassesOnlyWhenTrue() {
    assertTrue(evaluator.evaluatePredicate(bool(true), ROW));
    assertFalse(evaluator.evaluatePredicate(bool(false), ROW));
    assertFalse(evaluator.evaluatePredicate(bin(num(1), BinaryOp.EQ, nul()), ROW));
  }

  // helper biar ga nulis evaluator.evaluate(..., ROW) terus
  private Value eval(Expression expr) {
    return evaluator.evaluate(expr, ROW);
  }

  // cek ekspresinya error dengan kode yang diharapkan
  private void assertCode(ErrorCode code, Expression expr) {
    DbmsException error = assertThrows(DbmsException.class, () -> eval(expr));
    assertEquals(code, error.getCode());
  }

  // pembuat ekspresi biar test-nya pendek
  private static Expression bin(Expression left, BinaryOp op, Expression right) {
    return new BinaryExpression(left, op, right);
  }

  private static Expression col(String relation, String name) {
    return new ColumnExpression(new ColumnRef(Optional.ofNullable(relation), name));
  }

  private static Expression lit(Value value) {
    return new LiteralExpression(value);
  }

  private static Expression num(int value) {
    return lit(new IntValue(value));
  }

  private static Expression text(String value) {
    return lit(new TextValue(value));
  }

  private static Expression bool(boolean value) {
    return lit(new BooleanValue(value));
  }

  private static Expression nul() {
    return lit(NullValue.INSTANCE);
  }
}
