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
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TableSource;
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
import java.util.ArrayList;
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

  // subquery dijalanin sekali, nilai 7 = 7.0 dianggap sama, NOT IN membalik hasil
  @Test
  void inSubqueryRunsOnceAndChecksMembership() {
    List<Object> calls = new ArrayList<>();
    ExpressionEvaluator subqueries =
        new ExpressionEvaluator(
            plan -> {
              calls.add(plan);
              return List.of(new IntValue(1), new FloatValue(7.0));
            });
    Expression in = in(col("e", "id"), false);

    assertEquals(new BooleanValue(true), subqueries.evaluate(in, ROW));
    assertEquals(new BooleanValue(true), subqueries.evaluate(in, ROW));
    assertEquals(new BooleanValue(false), subqueries.evaluate(in(num(2), false), ROW));
    assertEquals(new BooleanValue(false), subqueries.evaluate(in(col("e", "id"), true), ROW));
    assertEquals(new BooleanValue(true), subqueries.evaluate(in(num(2), true), ROW));
    // ekspresi yang sama cuma sekali; empat ekspresi berbeda jadi empat kali
    assertEquals(4, calls.size());
  }

  // logika tiga nilai: NULL di operand atau di hasil subquery bikin hasil NULL kecuali kosong
  @Test
  void inSubqueryFollowsSqlNullSemantics() {
    ExpressionEvaluator withNull =
        new ExpressionEvaluator(plan -> List.of(new IntValue(1), NullValue.INSTANCE));
    ExpressionEvaluator empty = new ExpressionEvaluator(plan -> List.of());

    assertEquals(new BooleanValue(true), withNull.evaluate(in(num(1), false), ROW));
    assertEquals(NullValue.INSTANCE, withNull.evaluate(in(num(2), false), ROW));
    assertEquals(NullValue.INSTANCE, withNull.evaluate(in(num(2), true), ROW));
    assertEquals(NullValue.INSTANCE, withNull.evaluate(in(nul(), false), ROW));
    assertEquals(new BooleanValue(false), empty.evaluate(in(nul(), false), ROW));
    assertEquals(new BooleanValue(true), empty.evaluate(in(nul(), true), ROW));
  }

  // duplikat di hasil subquery ga ngaruh, CHAR berpadding tetap cocok, 7 IN (7.0) TRUE
  @Test
  void inSubqueryIgnoresDuplicatesPaddingAndNumericType() {
    ExpressionEvaluator values =
        new ExpressionEvaluator(
            plan -> List.of(new IntValue(3), new IntValue(3), new FloatValue(7.0)));
    ExpressionEvaluator texts = new ExpressionEvaluator(plan -> List.of(new TextValue("A  ")));

    assertEquals(new BooleanValue(true), values.evaluate(in(num(3), false), ROW));
    assertEquals(new BooleanValue(false), values.evaluate(in(num(3), true), ROW));
    assertEquals(new BooleanValue(true), values.evaluate(in(col("e", "id"), false), ROW));
    assertEquals(new BooleanValue(true), values.evaluate(in(lit(new FloatValue(3.0)), false), ROW));
    assertEquals(new BooleanValue(true), texts.evaluate(in(text("A"), false), ROW));
    assertEquals(new BooleanValue(false), texts.evaluate(in(text("B"), false), ROW));
  }

  // hasil NULL dari IN / NOT IN bikin baris ga lolos filter, sama kayak FALSE
  @Test
  void unknownInResultFailsThePredicate() {
    ExpressionEvaluator withNull =
        new ExpressionEvaluator(plan -> List.of(new IntValue(1), NullValue.INSTANCE));

    assertFalse(withNull.evaluatePredicate(in(num(2), false), ROW));
    assertFalse(withNull.evaluatePredicate(in(num(2), true), ROW));
    assertFalse(withNull.evaluatePredicate(in(nul(), true), ROW));
    assertTrue(withNull.evaluatePredicate(in(num(1), false), ROW));
    // NULL di dalam AND: FALSE menang, TRUE AND NULL tetap NULL
    assertEquals(
        new BooleanValue(false),
        withNull.evaluate(bin(in(num(2), false), BinaryOp.AND, bool(false)), ROW));
    assertEquals(
        NullValue.INSTANCE,
        withNull.evaluate(bin(bool(true), BinaryOp.AND, in(num(2), true)), ROW));
  }

  // evaluator biasa ga punya akses ke storage, jadi subquery harus ditolak jelas
  @Test
  void inSubqueryWithoutRunnerIsRejected() {
    assertCode(ErrorCode.INVALID_PLAN, in(num(1), false));
  }

  private static Expression in(Expression operand, boolean negated) {
    var query =
        new SelectStatement(
            List.of(new ColumnRef(Optional.of("d"), "id")),
            new TableSource("dept", "d"),
            Optional.empty(),
            List.of());
    return new InSubqueryExpression(
        operand, query, Optional.of(new TableScanPlan("dept", "d")), negated);
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
