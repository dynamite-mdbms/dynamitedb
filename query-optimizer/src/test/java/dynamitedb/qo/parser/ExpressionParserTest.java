package dynamitedb.qo.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.TextValue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExpressionParserTest {

  private final Lexer lexer = new Lexer();
  private final ExpressionParser parser = new ExpressionParser();

  private Expression parse(String text) {
    return parser.parseExpression(lexer.tokenize(text));
  }

  private static Expression lit(int value) {
    return new LiteralExpression(new IntValue(value));
  }

  private static Expression col(String name) {
    return new ColumnExpression(new ColumnRef(Optional.empty(), name));
  }

  private static Expression bin(Expression left, BinaryOp op, Expression right) {
    return new BinaryExpression(left, op, right);
  }

  @Test
  void parsesLiterals() {
    assertEquals(lit(123), parse("123"));
    assertEquals(new LiteralExpression(new FloatValue(3.14)), parse("3.14"));
    assertEquals(new LiteralExpression(new TextValue("hello world")), parse("'hello world'"));
    assertEquals(new LiteralExpression(new TextValue("it's")), parse("'it''s'"));
    assertEquals(new LiteralExpression(new TextValue("say \"hi\"")), parse("\"say \"\"hi\"\"\""));
  }

  @Test
  void parsesColumns() {
    assertEquals(col("salary"), parse("salary"));
    assertEquals(
        new ColumnExpression(new ColumnRef(Optional.of("emp"), "salary")), parse("emp.salary"));
  }

  @ParameterizedTest
  @CsvSource({
    "=, EQ", "<>, NEQ", "<, LT", "<=, LTE", ">, GT", ">=, GTE", "+, ADD", "-, SUB", "*, MUL",
    "/, DIV"
  })
  void parsesEveryBinaryOperator(String symbol, BinaryOp op) {
    assertEquals(bin(col("a"), op, lit(1)), parse("a " + symbol + " 1"));
  }

  @Test
  void parsesAndCaseInsensitively() {
    assertEquals(bin(col("a"), BinaryOp.AND, col("b")), parse("a and b"));
  }

  @Test
  void multiplicationBindsTighterThanAddition() {
    assertEquals(bin(lit(5), BinaryOp.ADD, bin(lit(3), BinaryOp.MUL, lit(2))), parse("5 + 3 * 2"));
    assertEquals(bin(bin(lit(6), BinaryOp.DIV, lit(3)), BinaryOp.SUB, lit(1)), parse("6 / 3 - 1"));
  }

  @Test
  void sameLevelOperatorsAreLeftAssociative() {
    assertEquals(bin(bin(lit(5), BinaryOp.SUB, lit(3)), BinaryOp.SUB, lit(2)), parse("5 - 3 - 2"));
    assertEquals(bin(bin(lit(8), BinaryOp.DIV, lit(4)), BinaryOp.MUL, lit(2)), parse("8 / 4 * 2"));
  }

  @Test
  void comparisonsSitUnderAnd() {
    Expression expected =
        bin(
            bin(
                bin(col("salary"), BinaryOp.MUL, new LiteralExpression(new FloatValue(1.05))),
                BinaryOp.GT,
                lit(5000)),
            BinaryOp.AND,
            bin(col("department"), BinaryOp.EQ, new LiteralExpression(new TextValue("IT"))));
    assertEquals(expected, parse("salary * 1.05 > 5000 AND department = 'IT'"));
  }

  @Test
  void parenthesesOverridePrecedence() {
    assertEquals(
        bin(bin(lit(5), BinaryOp.ADD, lit(3)), BinaryOp.MUL, lit(2)), parse("(5 + 3) * 2"));
    assertEquals(
        bin(col("a"), BinaryOp.AND, bin(col("b"), BinaryOp.AND, col("c"))),
        parse("a AND (b AND c)"));
  }

  @Test
  void parsesColumnToColumnComparison() {
    assertEquals(
        bin(
            new ColumnExpression(new ColumnRef(Optional.of("student"), "student_id")),
            BinaryOp.EQ,
            new ColumnExpression(new ColumnRef(Optional.of("enrollment"), "student_id"))),
        parse("student.student_id = enrollment.student_id"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "salary >",
        "salary + * 2",
        "(salary > 10",
        "salary > 10)",
        "a b",
        "()",
        "a AND",
        "emp.",
        "a = 1;",
        "-5",
        "ORDER",
        "99999999999"
      })
  void rejectsInvalidExpressionsWithSyntaxError(String text) {
    DbmsException failure = assertThrows(DbmsException.class, () -> parse(text));
    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertTrue(
        failure.getMessage().contains("offset") || failure.getMessage().contains("end of input"),
        failure.getMessage());
  }

  @Test
  void reportsOffsetOfOffendingToken() {
    DbmsException failure = assertThrows(DbmsException.class, () -> parse("salary + * 2"));
    assertEquals("Expected expression at offset 9 near '*'", failure.getMessage());

    DbmsException incomplete = assertThrows(DbmsException.class, () -> parse("salary >"));
    assertEquals("Expected expression at end of input", incomplete.getMessage());
  }

  @Test
  void rejectsEmptyInput() {
    assertThrows(DbmsException.class, () -> parser.parseExpression(List.of()));
    assertThrows(DbmsException.class, () -> parser.parseExpression(null));
  }
}
