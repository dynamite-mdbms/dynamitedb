package dynamitedb.qo.parser;

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
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Membentuk pohon ekspresi sesuai prioritas operator (QO-04). */
public class ExpressionParser {

  /** Tingkat operator dari prioritas terendah ke tertinggi; semuanya left-associative. */
  private static final List<Map<String, BinaryOp>> LEVELS =
      List.of(
          Map.of("AND", BinaryOp.AND),
          Map.of(
              "=", BinaryOp.EQ,
              "<>", BinaryOp.NEQ,
              "<", BinaryOp.LT,
              "<=", BinaryOp.LTE,
              ">", BinaryOp.GT,
              ">=", BinaryOp.GTE),
          Map.of("+", BinaryOp.ADD, "-", BinaryOp.SUB),
          Map.of("*", BinaryOp.MUL, "/", BinaryOp.DIV));

  /** Indeks tingkat perbandingan di LEVELS; IS [NOT] NULL berada tepat di bawahnya. */
  private static final int COMPARISON_LEVEL = 1;

  public Expression parseExpression(List<Token> tokens) {
    if (tokens == null || tokens.isEmpty()) throw syntax("Expected expression", null);
    int[] cursor = {0};
    Expression expression = parseLevel(tokens, cursor, 0);
    if (cursor[0] < tokens.size()) {
      throw syntax("Unexpected token in expression", tokens.get(cursor[0]));
    }
    return expression;
  }

  private static Expression parseLevel(List<Token> tokens, int[] cursor, int level) {
    if (level == LEVELS.size()) return parsePrimary(tokens, cursor);
    Expression left = parseLevel(tokens, cursor, level + 1);
    BinaryOp operator;
    while ((operator = operatorAt(tokens, cursor[0], LEVELS.get(level))) != null) {
      cursor[0]++;
      left = new BinaryExpression(left, operator, parseLevel(tokens, cursor, level + 1));
    }
    return level == COMPARISON_LEVEL ? parseIsNull(tokens, cursor, left) : left;
  }

  /** Postfix {@code IS [NOT] NULL}; prioritasnya di bawah perbandingan seperti di PostgreSQL. */
  private static Expression parseIsNull(List<Token> tokens, int[] cursor, Expression operand) {
    if (!isKeyword(tokenAt(tokens, cursor[0]), "IS")) return operand;
    cursor[0]++;
    boolean negated = isKeyword(tokenAt(tokens, cursor[0]), "NOT");
    if (negated) cursor[0]++;
    Token nullToken = tokenAt(tokens, cursor[0]);
    if (!isKeyword(nullToken, "NULL")) throw syntax("Expected NULL after IS", nullToken);
    cursor[0]++;
    return new BinaryExpression(
        operand,
        negated ? BinaryOp.IS_NOT : BinaryOp.IS,
        new LiteralExpression(NullValue.INSTANCE));
  }

  private static boolean isKeyword(Token token, String keyword) {
    return token != null
        && token.kind() == TokenKind.KEYWORD
        && token.lexeme().equalsIgnoreCase(keyword);
  }

  private static BinaryOp operatorAt(List<Token> tokens, int index, Map<String, BinaryOp> ops) {
    if (index >= tokens.size()) return null;
    Token token = tokens.get(index);
    return ops.get(token.lexeme().toUpperCase(Locale.ROOT));
  }

  private static Expression parsePrimary(List<Token> tokens, int[] cursor) {
    Token token = tokenAt(tokens, cursor[0]);
    if (token == null) throw syntax("Expected expression", null);

    if (token.kind() == TokenKind.SYMBOL && token.lexeme().equals("(")) {
      cursor[0]++;
      Expression inner = parseLevel(tokens, cursor, 0);
      Token closing = tokenAt(tokens, cursor[0]);
      if (closing == null || !closing.lexeme().equals(")")) {
        throw syntax("Expected ')' to close '(' at offset " + token.offset(), closing);
      }
      cursor[0]++;
      return inner;
    }

    if (token.kind() == TokenKind.LITERAL) {
      cursor[0]++;
      return new LiteralExpression(literalValue(token));
    }

    if (token.kind() == TokenKind.KEYWORD && token.lexeme().equalsIgnoreCase("NULL")) {
      cursor[0]++;
      return new LiteralExpression(NullValue.INSTANCE);
    }

    if (token.kind() == TokenKind.IDENTIFIER) {
      cursor[0]++;
      Token dot = tokenAt(tokens, cursor[0]);
      if (dot == null || !dot.lexeme().equals(".")) {
        return new ColumnExpression(new ColumnRef(Optional.empty(), token.lexeme()));
      }
      cursor[0]++;
      Token column = tokenAt(tokens, cursor[0]);
      if (column == null || column.kind() != TokenKind.IDENTIFIER) {
        throw syntax("Expected a column name after '.'", column);
      }
      cursor[0]++;
      return new ColumnExpression(new ColumnRef(Optional.of(token.lexeme()), column.lexeme()));
    }

    throw syntax("Expected expression", token);
  }

  private static Value literalValue(Token token) {
    String lexeme = token.lexeme();
    char first = lexeme.charAt(0);
    if (first == '\'' || first == '"') {
      // Lexer menjamin quote penutup; quote ganda di dalam literal adalah escape.
      String quote = String.valueOf(first);
      return new TextValue(lexeme.substring(1, lexeme.length() - 1).replace(quote + quote, quote));
    }
    if (lexeme.contains(".")) return new FloatValue(Double.parseDouble(lexeme));
    try {
      return new IntValue(Integer.parseInt(lexeme));
    } catch (NumberFormatException e) {
      throw syntax("Integer literal out of range", token);
    }
  }

  private static Token tokenAt(List<Token> tokens, int index) {
    return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
  }

  /** Format pesan sama dengan {@link SqlParser}: offset dan lexeme, atau akhir input. */
  static DbmsException syntax(String message, Token token) {
    if (token == null) return new DbmsException(ErrorCode.SQL_SYNTAX, message + " at end of input");
    return new DbmsException(
        ErrorCode.SQL_SYNTAX,
        message + " at offset " + token.offset() + " near '" + token.lexeme() + "'");
  }
}
