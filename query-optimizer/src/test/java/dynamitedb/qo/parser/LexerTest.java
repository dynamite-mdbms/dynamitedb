package dynamitedb.qo.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexerTest {

  private final Lexer lexer = new Lexer();

  @Test
  void tokenizesCaseInsensitiveMultilineSqlAndKeepsOffsets() {
    String sql = "select s.id,\n s.gpa >= 3.5 FROM student;";

    List<Token> tokens = lexer.tokenize(sql);

    assertEquals(TokenKind.KEYWORD, tokens.get(0).kind());
    assertEquals("select", tokens.get(0).lexeme());
    assertEquals(0, tokens.get(0).offset());
    assertTrue(tokens.stream().anyMatch(token -> token.lexeme().equals(">=")));
    assertTrue(tokens.stream().anyMatch(token -> token.lexeme().equals("3.5")));
    assertEquals(";", tokens.get(tokens.size() - 1).lexeme());
  }

  @Test
  void keepsQuotedTextAsOneLiteral() {
    List<Token> tokens = lexer.tokenize("SELECT 'hello world' FROM messages");

    assertEquals(TokenKind.LITERAL, tokens.get(1).kind());
    assertEquals("'hello world'", tokens.get(1).lexeme());
  }

  @Test
  void rejectsUnterminatedTextWithSyntaxOffset() {
    DbmsException failure =
        assertThrows(DbmsException.class, () -> lexer.tokenize("SELECT 'broken FROM t"));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertTrue(failure.getMessage().contains("offset 7"));
  }

  @Test
  void rejectsUnknownCharacter() {
    DbmsException failure = assertThrows(DbmsException.class, () -> lexer.tokenize("SELECT @"));

    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertTrue(failure.getMessage().contains("offset 7"));
  }
}
