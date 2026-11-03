package dynamitedb.qo.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortDirection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
}
