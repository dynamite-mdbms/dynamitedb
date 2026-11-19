package dynamitedb.qo.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregateFunction;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateTableStatement;
import dynamitedb.contracts.query.DropTableStatement;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.storage.IntValue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlParserTest {

  private final Lexer lexer = new Lexer();
  private final SqlParser parser = new SqlParser(new StatementParser(), new ExpressionParser());

  @Test
  void parsesSelectProjectionWhereAndDefaultSortDirection() {
    SelectStatement select =
        assertInstanceOf(
            SelectStatement.class,
            parser.parse(
                lexer.tokenize(
                    "SELECT student.name, student.gpa FROM student "
                        + "WHERE student.gpa >= 3.5 ORDER BY student.name;")));

    assertEquals(2, select.projection().size());
    assertEquals(Optional.of("student"), select.projection().get(0).relation());
    assertTrue(select.predicate().isPresent());
    assertEquals(SortDirection.ASC, select.orderBy().get(0).direction());
  }

  @Test
  void routesTableDdlToStatementParser() {
    assertInstanceOf(
        CreateTableStatement.class, parser.parse(lexer.tokenize("CREATE TABLE t (id INTEGER)")));
    assertInstanceOf(DropTableStatement.class, parser.parse(lexer.tokenize("DROP TABLE t;")));
  }

  @Test
  void parsesMixedJoinsFromLeftToRight() {
    SelectStatement select =
        assertInstanceOf(
            SelectStatement.class,
            parser.parse(
                lexer.tokenize(
                    "SELECT * FROM student, enrollment "
                        + "JOIN course ON enrollment.course_id = course.course_id")));

    JoinSource outer = assertInstanceOf(JoinSource.class, select.from());
    assertEquals(JoinKind.THETA, outer.kind());
    JoinSource inner = assertInstanceOf(JoinSource.class, outer.left());
    assertEquals(JoinKind.CARTESIAN, inner.kind());
    assertTrue(outer.condition().isPresent());
  }

  @Test
  void parsesNaturalJoinAndExplicitDescendingSort() {
    SelectStatement select =
        assertInstanceOf(
            SelectStatement.class,
            parser.parse(
                lexer.tokenize(
                    "SELECT student_id FROM student NATURAL JOIN enrollment "
                        + "ORDER BY student_id DESC")));

    JoinSource join = assertInstanceOf(JoinSource.class, select.from());
    assertEquals(JoinKind.NATURAL, join.kind());
    assertTrue(join.condition().isEmpty());
    assertEquals(SortDirection.DESC, select.orderBy().get(0).direction());
  }

  @Test
  void parsesMultipleSortKeys() {
    SelectStatement select =
        assertInstanceOf(
            SelectStatement.class,
            parser.parse(
                lexer.tokenize(
                    "SELECT student_id FROM student ORDER BY student_id DESC, name ASC")));

    assertEquals(2, select.orderBy().size());
    assertEquals("student_id", select.orderBy().get(0).column().column());
    assertEquals(SortDirection.DESC, select.orderBy().get(0).direction());
    assertEquals("name", select.orderBy().get(1).column().column());
    assertEquals(SortDirection.ASC, select.orderBy().get(1).direction());
  }

  @Test
  void parsesExpressionAliasesAndAggregateClauses() {
    SelectStatement select =
        assertInstanceOf(
            SelectStatement.class,
            parser.parse(
                lexer.tokenize(
                    "SELECT sid + 1 AS next_sid, COUNT(*) AS total FROM student "
                        + "GROUP BY sid HAVING COUNT(*) > 1 ORDER BY total DESC")));

    assertEquals(2, select.items().size());
    assertEquals(Optional.of("next_sid"), select.items().get(0).alias());
    assertEquals(
        new BinaryExpression(
            new ColumnExpression(new ColumnRef(Optional.empty(), "sid")),
            BinaryOp.ADD,
            new LiteralExpression(new IntValue(1))),
        select.items().get(0).expression());
    assertEquals(
        new AggregateExpression(AggregateFunction.COUNT, Optional.empty()),
        select.items().get(1).expression());
    assertEquals(List.of(new ColumnRef(Optional.empty(), "sid")), select.groupBy());
    assertTrue(select.having().isPresent());
    assertEquals("total", select.orderBy().get(0).column().column());
  }
  
  void parsesLimitAsTheLastClause() {
    assertEquals(Optional.empty(), select("SELECT student_id FROM student").limit());
    assertEquals(
        Optional.of(5L),
        select("SELECT student_id FROM student ORDER BY student_id DESC LIMIT 5;").limit());
    assertEquals(
        Optional.of(0L), select("SELECT student_id FROM student WHERE gpa > 3 limit 0").limit());
    assertEquals(
        Optional.of(2L),
        select("SELECT * FROM student JOIN enrollment ON student.id = enrollment.id LIMIT 2")
            .limit());
    assertEquals(
        Optional.of(1L), select("SELECT * FROM student NATURAL JOIN enrollment LIMIT 1").limit());
    // The accepted range is 0 to Long.MAX_VALUE.
    assertEquals(
        Optional.of(Long.MAX_VALUE),
        select("SELECT * FROM student LIMIT 9223372036854775807").limit());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM student LIMIT",
        "SELECT * FROM student LIMIT;",
        "SELECT * FROM student LIMIT -1",
        "SELECT * FROM student LIMIT 1.5",
        "SELECT * FROM student LIMIT '3'",
        "SELECT * FROM student LIMIT gpa",
        "SELECT * FROM student LIMIT (1)",
        "SELECT * FROM student LIMIT 1 + 1",
        "SELECT * FROM student LIMIT 9223372036854775808",
        "SELECT * FROM student LIMIT 1 2",
        "SELECT * FROM student LIMIT 1 LIMIT 2",
        "SELECT * FROM student LIMIT 1 ORDER BY name",
        "SELECT * FROM student LIMIT 1 WHERE gpa > 3",
        "SELECT * LIMIT 1 FROM student",
        "SELECT * FROM student WHERE LIMIT 1",
        "SELECT * FROM student ORDER BY LIMIT 1",
        "SELECT * FROM student JOIN enrollment ON LIMIT 1",
        "UPDATE student SET gpa = 4 WHERE gpa > 3 LIMIT 1",
        "DELETE FROM student WHERE gpa > 3 LIMIT 1",
      })
  void rejectsMalformedOrMisplacedLimit(String sql) {
    DbmsException failure =
        assertThrows(DbmsException.class, () -> parser.parse(lexer.tokenize(sql)));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
  }

  @Test
  void reportsLimitRangeAndMissingValue() {
    assertTrue(
        assertThrows(
                DbmsException.class,
                () -> parser.parse(lexer.tokenize("SELECT * FROM t LIMIT 99999999999999999999")))
            .getMessage()
            .contains("out of range"));
    assertTrue(
        assertThrows(
                DbmsException.class, () -> parser.parse(lexer.tokenize("SELECT * FROM t LIMIT -1")))
            .getMessage()
            .contains("non-negative integer after LIMIT"));
  }

  private SelectStatement select(String sql) {
    return assertInstanceOf(SelectStatement.class, parser.parse(lexer.tokenize(sql)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT student_id FROM student ORDER BY ,student_id",
        "SELECT student_id FROM student ORDER BY student_id,,name"
      })
  void rejectsMalformedOrderByCommas(String sql) {
    DbmsException failure =
        assertThrows(DbmsException.class, () -> parser.parse(lexer.tokenize(sql)));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
  }

  @Test
  void rejectsMisspelledStatementWithPosition() {
    DbmsException failure =
        assertThrows(DbmsException.class, () -> parser.parse(lexer.tokenize("SELEC * FROM t")));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertTrue(failure.getMessage().contains("offset 0"));
  }

  @Test
  void rejectsIncompleteClause() {
    DbmsException failure =
        assertThrows(
            DbmsException.class,
            () -> parser.parse(lexer.tokenize("SELECT name FROM student WHERE")));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertTrue(failure.getMessage().contains("end of input"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELEC * FROM student",
        "SELECT * FORM student",
        "SELECT * FROM",
        "SELECT * FROM;",
        "SELECT * FROM student WHERE gpa >;",
        "SELECT * FROM student ORDER BY",
        "SELECT * WHERE gpa > 3 FROM student",
        "SELECT * FROM student ORDER BY name WHERE gpa > 3",
        "SELECT * FROM student WHERE (gpa > 3",
        "SELECT * FROM student WHERE gpa > 3)",
        "SELECT * FROM student WHERE name = 'Alya",
        "SELECT * FROM student JOIN enrollment",
      })
  void rejectsInvalidSpecificationQueriesAsSyntaxErrors(String sql) {
    DbmsException failure =
        assertThrows(DbmsException.class, () -> parser.parse(lexer.tokenize(sql)));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO student VALUES (1, 'Ann', HUGE, 'A');",
        "UPDATE student SET gpa = HUGE WHERE student_id = 1;",
        "UPDATE student SET gpa = 3.0 WHERE gpa = HUGE;",
        "DELETE FROM student WHERE gpa = HUGE;",
        "SELECT * FROM student WHERE gpa > HUGE;",
      })
  void rejectsOverflowingFloatLiteralInEveryStatementKind(String template) {
    String huge = "1" + "0".repeat(309) + ".0";
    DbmsException failure =
        assertThrows(
            DbmsException.class,
            () -> parser.parse(lexer.tokenize(template.replace("HUGE", huge))));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertEquals(
        "Float literal out of range at offset " + template.indexOf("HUGE") + " near '" + huge + "'",
        failure.getMessage());
  }
}
