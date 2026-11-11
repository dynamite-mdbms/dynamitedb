package dynamitedb.qo.parser;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Memecah teks SQL menjadi token (QO-01). */
public class Lexer {

  private static final Set<String> KEYWORDS =
      Set.of(
          "SELECT",
          "FROM",
          "WHERE",
          "ORDER",
          "BY",
          "ASC",
          "DESC",
          "JOIN",
          "ON",
          "NATURAL",
          "AND",
          "NULL",
          "IS",
          "NOT",
          "INSERT",
          "INTO",
          "VALUES",
          "UPDATE",
          "SET",
          "DELETE",
          "BEGIN",
          "TRANSACTION",
          "COMMIT",
          "ABORT",
          "CREATE",
          "INDEX",
          "AS",
          "HASH",
          "B_PLUS_TREE");

  public List<Token> tokenize(String sql) {
    if (sql == null) {
      throw syntax("SQL must not be null", 0);
    }

    List<Token> tokens = new ArrayList<>();
    int cursor = 0;
    while (cursor < sql.length()) {
      char current = sql.charAt(cursor);
      if (Character.isWhitespace(current)) {
        cursor++;
        continue;
      }

      int start = cursor;
      if (Character.isLetter(current) || current == '_') {
        cursor++;
        while (cursor < sql.length()) {
          char next = sql.charAt(cursor);
          if (!Character.isLetterOrDigit(next) && next != '_') break;
          cursor++;
        }
        String lexeme = sql.substring(start, cursor);
        TokenKind kind =
            KEYWORDS.contains(lexeme.toUpperCase(Locale.ROOT))
                ? TokenKind.KEYWORD
                : TokenKind.IDENTIFIER;
        tokens.add(new Token(kind, lexeme, start));
        continue;
      }

      if (Character.isDigit(current)) {
        cursor++;
        while (cursor < sql.length() && Character.isDigit(sql.charAt(cursor))) cursor++;
        if (cursor < sql.length() && sql.charAt(cursor) == '.') {
          int dot = cursor++;
          if (cursor >= sql.length() || !Character.isDigit(sql.charAt(cursor))) {
            throw syntax("Expected digits after decimal point", dot);
          }
          while (cursor < sql.length() && Character.isDigit(sql.charAt(cursor))) cursor++;
        }
        tokens.add(new Token(TokenKind.LITERAL, sql.substring(start, cursor), start));
        continue;
      }

      if (current == '\'' || current == '"') {
        char quote = current;
        cursor++;
        boolean closed = false;
        while (cursor < sql.length()) {
          if (sql.charAt(cursor) != quote) {
            cursor++;
            continue;
          }
          if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == quote) {
            cursor += 2;
            continue;
          }
          cursor++;
          closed = true;
          break;
        }
        if (!closed) throw syntax("Unterminated text literal", start);
        tokens.add(new Token(TokenKind.LITERAL, sql.substring(start, cursor), start));
        continue;
      }

      if (cursor + 1 < sql.length()) {
        String pair = sql.substring(cursor, cursor + 2);
        if (pair.equals("<>") || pair.equals(">=") || pair.equals("<=")) {
          tokens.add(new Token(TokenKind.SYMBOL, pair, start));
          cursor += 2;
          continue;
        }
      }

      if (",().;=><+-*/".indexOf(current) >= 0) {
        tokens.add(new Token(TokenKind.SYMBOL, Character.toString(current), start));
        cursor++;
        continue;
      }

      throw syntax("Unexpected character '" + current + "'", start);
    }
    return List.copyOf(tokens);
  }

  private static DbmsException syntax(String message, int offset) {
    return new DbmsException(ErrorCode.SQL_SYNTAX, message + " at offset " + offset);
  }
}
