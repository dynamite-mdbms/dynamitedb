package dynamitedb.qo.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @Test
  void arithmeticBindsTighterThanComparison() {
    assertEquals(
        bin(bin(col("a"), BinaryOp.ADD, lit(1)), BinaryOp.GT, bin(col("b"), BinaryOp.SUB, lit(2))),
        parse("a + 1 > b - 2"));
  }

  // Pesan lengkap dicek agar terbukti aturan mana yang menolak input dan di offset mana.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          salary >      | Expected expression at end of input
          salary + * 2  | Expected expression at offset 9 near '*'
          (salary > 10  | Expected ')' to close '(' at offset 0 at end of input
          salary > 10)  | Unexpected token in expression at offset 11 near ')'
          a b           | Unexpected token in expression at offset 2 near 'b'
          ()            | Expected expression at offset 1 near ')'
          a AND         | Expected expression at end of input
          emp.          | Expected a column name after '.' at end of input
          emp.5         | Expected a column name after '.' at offset 4 near '5'
          a = 1;        | Unexpected token in expression at offset 5 near ';'
          -5            | Expected expression at offset 0 near '-'
          ORDER         | Expected expression at offset 0 near 'ORDER'
          99999999999   | Integer literal out of range at offset 0 near '99999999999'
          """)
  void rejectsInvalidExpressionAtOffendingToken(String text, String message) {
    DbmsException failure = assertThrows(DbmsException.class, () -> parse(text));
    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertEquals(message, failure.getMessage());
  }

  @Test
  void rejectsFloatLiteralThatOverflowsToInfinity() {
    String huge = "9".repeat(400) + ".0";
    assertFloatOutOfRange(huge, 0, huge);
    // Pangkat sepuluh terkecil yang melewati Double.MAX_VALUE (~1.8e308).
    String smallestOver = "1" + "0".repeat(309) + ".0";
    assertFloatOutOfRange(smallestOver, 0, smallestOver);
  }

  @Test
  void rejectsOverflowInsideLargerExpressionAtLiteralOffset() {
    String huge = "1" + "0".repeat(309) + ".0";
    assertFloatOutOfRange("a = " + huge, 4, huge);
    assertFloatOutOfRange("a + " + huge, 4, huge);
    assertFloatOutOfRange("(" + huge + ")", 1, huge);
  }

  @Test
  void acceptsDoubleMaxValueWrittenAsPlainDecimal() {
    String max = new BigDecimal(Double.MAX_VALUE).toPlainString() + ".0";
    assertEquals(new LiteralExpression(new FloatValue(Double.MAX_VALUE)), parse(max));
  }

  @Test
  void roundsToNearestAtTheUpperBoundary() {
    // Titik tengah MAX + ulp/2 dibulatkan ke genap (Infinity); tepat di bawahnya masih ke MAX.
    BigDecimal tie =
        new BigDecimal(Double.MAX_VALUE).add(new BigDecimal(Math.ulp(Double.MAX_VALUE) / 2));
    String belowTie = tie.subtract(BigDecimal.ONE).toPlainString() + ".0";
    assertEquals(new LiteralExpression(new FloatValue(Double.MAX_VALUE)), parse(belowTie));
    String atTie = tie.toPlainString() + ".0";
    assertFloatOutOfRange(atTie, 0, atTie);
  }

  @Test
  void acceptsUnderflowingLiteralAsZeroInsteadOfRejecting() {
    assertEquals(new LiteralExpression(new FloatValue(0.0)), parse("0." + "0".repeat(400) + "1"));
  }

  @Test
  void acceptsLongFractionAndLeadingZeros() {
    assertEquals(new LiteralExpression(new FloatValue(14.0 / 9.0)), parse("1." + "5".repeat(400)));
    assertEquals(new LiteralExpression(new FloatValue(1.5)), parse("000001.5"));
  }

  @Test
  void keepsIntegerOutOfRangeMessageForLongIntegerLiterals() {
    String digits = "9".repeat(400);
    DbmsException failure = assertThrows(DbmsException.class, () -> parse(digits));
    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertEquals(
        "Integer literal out of range at offset 0 near '" + digits + "'", failure.getMessage());
  }

  private void assertFloatOutOfRange(String text, int offset, String lexeme) {
    DbmsException failure = assertThrows(DbmsException.class, () -> parse(text));
    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertEquals(
        "Float literal out of range at offset " + offset + " near '" + lexeme + "'",
        failure.getMessage());
  }

  @Test
  void rejectsEmptyInput() {
    for (List<Token> tokens : Arrays.asList(List.<Token>of(), null)) {
      DbmsException failure =
          assertThrows(DbmsException.class, () -> parser.parseExpression(tokens));
      assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
      assertEquals("Expected expression at end of input", failure.getMessage());
    }
  }
}
