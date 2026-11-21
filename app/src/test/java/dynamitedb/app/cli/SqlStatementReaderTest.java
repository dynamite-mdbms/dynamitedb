package dynamitedb.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dynamitedb.qo.parser.Lexer;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class SqlStatementReaderTest {

  @Test
  void commentsWithSemicolonsRemainOneExecutableStatement() throws Exception {
    String input =
        "-- select one student;\n"
            + "SELECT /* semicolon; inside block comment */ student_id FROM student;";
    SqlStatementReader reader = new SqlStatementReader(new StringReader(input));

    String statement = reader.next();

    assertEquals(input, statement);
    assertEquals("SELECT", new Lexer().tokenize(statement).get(0).lexeme());
    assertNull(reader.next());
  }

  @Test
  void pastedMultilineSqlKeepsTheCurrentStatementAndFollowingStatement() throws Exception {
    SqlStatementReader reader =
        new SqlStatementReader(new StringReader("SELECT 'existing';\nSELECT\n  2;\nSELECT 3;"));

    assertEquals("SELECT 'existing';", reader.next());
    assertEquals("SELECT\n  2;", reader.next());
    assertEquals("SELECT 3;", reader.next());
    assertNull(reader.next());
  }
}
