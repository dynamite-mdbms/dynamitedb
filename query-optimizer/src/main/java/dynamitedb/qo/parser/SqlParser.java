package dynamitedb.qo.parser;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FromSource;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.SelectItem;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class SqlParser {

  private static final Set<String> NON_SELECT_STATEMENTS =
      Set.of("INSERT", "UPDATE", "DELETE", "BEGIN", "COMMIT", "ABORT", "CREATE", "DROP");

  private final StatementParser statements;
  private final ExpressionParser expressions;

  public SqlParser(StatementParser statements, ExpressionParser expressions) {
    this.statements = statements;
    this.expressions = expressions;
  }

  public Statement parse(List<Token> tokens) {
    if (tokens == null || tokens.isEmpty()) throw syntax("Expected an SQL statement", null);
    if (is(tokens.get(0), "SELECT")) return parseSelect(tokens);
    if (!NON_SELECT_STATEMENTS.contains(upper(tokens.get(0)))) {
      throw syntax("Expected a supported statement keyword", tokens.get(0));
    }
    return statements.parseStatement(tokens);
  }

  private SelectStatement parseSelect(List<Token> tokens) {
    int[] cursor = {1};
    List<SelectItem> items = parseSelectItems(tokens, cursor);
    expect(tokens, cursor, "FROM");
    FromSource from = parseFrom(tokens, cursor);

    Optional<Expression> predicate = Optional.empty();
    if (peek(tokens, cursor, "WHERE")) {
      cursor[0]++;
      int end = findNextClause(tokens, cursor[0]);
      if (end == cursor[0])
        throw syntax("Expected a condition after WHERE", tokenAt(tokens, cursor[0]));
      predicate = Optional.of(expressions.parseExpression(tokens.subList(cursor[0], end)));
      cursor[0] = end;
    }

    List<ColumnRef> groupBy = List.of();
    if (peek(tokens, cursor, "GROUP")) {
      cursor[0]++;
      expect(tokens, cursor, "BY");
      int end = findNextClause(tokens, cursor[0]);
      groupBy = parseColumnSegment(tokens, cursor[0], end);
      cursor[0] = end;
    }

    Optional<Expression> having = Optional.empty();
    if (peek(tokens, cursor, "HAVING")) {
      cursor[0]++;
      int end = findNextClause(tokens, cursor[0]);
      if (end == cursor[0]) throw syntax("Expected a condition after HAVING", tokenAt(tokens, end));
      having = Optional.of(expressions.parseExpression(tokens.subList(cursor[0], end)));
      cursor[0] = end;
    }

    List<SortKey> orderBy = List.of();
    if (peek(tokens, cursor, "ORDER")) {
      cursor[0]++;
      expect(tokens, cursor, "BY");
      orderBy = parseSortKeys(tokens, cursor);
    }

    Optional<Long> limit = Optional.empty();
    if (peek(tokens, cursor, "LIMIT")) {
      cursor[0]++;
      limit = Optional.of(parseLimit(tokens, cursor));
    }

    consumeTerminator(tokens, cursor);
    if (cursor[0] != tokens.size()) {
      throw syntax("Unexpected token after SELECT statement", tokens.get(cursor[0]));
    }
    return new SelectStatement(items, from, predicate, groupBy, having, orderBy, limit);
  }

  private List<SelectItem> parseSelectItems(List<Token> tokens, int[] cursor) {
    int end = findTopLevel(tokens, cursor[0], "FROM");
    if (end == cursor[0]) throw syntax("Expected a projection before FROM", tokenAt(tokens, end));
    List<SelectItem> items = new ArrayList<>();
    int start = cursor[0];
    int depth = 0;
    for (int index = start; index <= end; index++) {
      boolean boundary = index == end;
      if (!boundary) {
        Token token = tokens.get(index);
        if (is(token, "(")) depth++;
        else if (is(token, ")")) depth--;
        boundary = depth == 0 && is(token, ",");
      }
      if (!boundary) continue;
      items.add(parseSelectItem(tokens, start, index));
      start = index + 1;
    }
    cursor[0] = end;
    return List.copyOf(items);
  }

  private SelectItem parseSelectItem(List<Token> tokens, int start, int end) {
    int aliasIndex = findTopLevel(tokens.subList(start, end), 0, "AS");
    Optional<String> alias = Optional.empty();
    int expressionEnd = end;
    if (aliasIndex < end - start) {
      Token name = tokenAt(tokens, start + aliasIndex + 1);
      if (name == null || name.kind() != TokenKind.IDENTIFIER || start + aliasIndex + 2 != end) {
        throw syntax("Expected an output alias after AS", name);
      }
      alias = Optional.of(name.lexeme());
      expressionEnd = start + aliasIndex;
    }

    List<Token> expressionTokens = tokens.subList(start, expressionEnd);
    Expression expression;
    if (expressionTokens.size() == 1 && is(expressionTokens.get(0), "*")) {
      expression = new ColumnExpression(new ColumnRef(Optional.empty(), "*"));
    } else if (expressionTokens.size() == 3
        && expressionTokens.get(1).lexeme().equals(".")
        && is(expressionTokens.get(2), "*")) {
      expression =
          new ColumnExpression(new ColumnRef(Optional.of(expressionTokens.get(0).lexeme()), "*"));
    } else {
      expression = expressions.parseExpression(expressionTokens);
    }
    if (expression instanceof ColumnExpression column && column.column().column().equals("*")) {
      if (alias.isPresent())
        throw syntax("A wildcard cannot have an output alias", tokens.get(start));
    }
    return new SelectItem(expression, alias);
  }

  private static List<ColumnRef> parseColumnSegment(List<Token> tokens, int start, int end) {
    List<ColumnRef> columns = new ArrayList<>();
    int cursor = start;
    while (cursor < end) {
      int[] current = {cursor};
      columns.add(parseColumn(tokens, current));
      cursor = current[0];
      if (cursor < end) {
        if (!is(tokens.get(cursor), ","))
          throw syntax("Expected ',' in GROUP BY", tokens.get(cursor));
        cursor++;
      }
    }
    if (columns.isEmpty()) throw syntax("Expected a column after GROUP BY", tokenAt(tokens, end));
    return List.copyOf(columns);
  }

  /** {@code LIMIT n}: n is an unsigned integer literal from 0 to {@link Long#MAX_VALUE}. */
  private static long parseLimit(List<Token> tokens, int[] cursor) {
    Token count = tokenAt(tokens, cursor[0]);
    if (count == null
        || count.kind() != TokenKind.LITERAL
        || !count.lexeme().chars().allMatch(c -> c >= '0' && c <= '9')) {
      throw syntax("Expected a non-negative integer after LIMIT", count);
    }
    cursor[0]++;
    try {
      return Long.parseLong(count.lexeme());
    } catch (NumberFormatException e) {
      throw syntax("LIMIT value out of range", count);
    }
  }

  private FromSource parseFrom(List<Token> tokens, int[] cursor) {
    FromSource source = parseTable(tokens, cursor);
    while (cursor[0] < tokens.size()) {
      if (peek(tokens, cursor, ",")) {
        cursor[0]++;
        source =
            new JoinSource(
                source, parseTable(tokens, cursor), JoinKind.CARTESIAN, Optional.empty());
      } else if (peek(tokens, cursor, "NATURAL")) {
        cursor[0]++;
        expect(tokens, cursor, "JOIN");
        source =
            new JoinSource(source, parseTable(tokens, cursor), JoinKind.NATURAL, Optional.empty());
      } else if (peek(tokens, cursor, "JOIN")) {
        cursor[0]++;
        FromSource right = parseTable(tokens, cursor);
        expect(tokens, cursor, "ON");
        int end = findJoinConditionEnd(tokens, cursor[0]);
        if (end == cursor[0])
          throw syntax("Expected a condition after ON", tokenAt(tokens, cursor[0]));
        Expression condition = expressions.parseExpression(tokens.subList(cursor[0], end));
        source = new JoinSource(source, right, JoinKind.THETA, Optional.of(condition));
        cursor[0] = end;
      } else {
        break;
      }
    }
    return source;
  }

  private static TableSource parseTable(List<Token> tokens, int[] cursor) {
    Token table = requireIdentifier(tokens, cursor, "Expected a table name");
    String alias = table.lexeme();
    if (peek(tokens, cursor, "AS")) {
      cursor[0]++;
      alias = requireIdentifier(tokens, cursor, "Expected an alias after AS").lexeme();
    }
    return new TableSource(table.lexeme(), alias);
  }

  private static List<SortKey> parseSortKeys(List<Token> tokens, int[] cursor) {
    List<SortKey> keys = new ArrayList<>();
    while (true) {
      ColumnRef column = parseColumn(tokens, cursor);
      SortDirection direction = SortDirection.ASC;
      if (peek(tokens, cursor, "ASC")) {
        cursor[0]++;
      } else if (peek(tokens, cursor, "DESC")) {
        direction = SortDirection.DESC;
        cursor[0]++;
      }
      keys.add(new SortKey(column, direction));
      if (!peek(tokens, cursor, ",")) break;
      cursor[0]++;
    }
    return List.copyOf(keys);
  }

  private static ColumnRef parseColumn(List<Token> tokens, int[] cursor) {
    if (peek(tokens, cursor, "*")) {
      cursor[0]++;
      return new ColumnRef(Optional.empty(), "*");
    }
    Token first = requireIdentifier(tokens, cursor, "Expected a column name");
    if (!peek(tokens, cursor, ".")) return new ColumnRef(Optional.empty(), first.lexeme());
    cursor[0]++;
    if (peek(tokens, cursor, "*")) {
      cursor[0]++;
      return new ColumnRef(Optional.of(first.lexeme()), "*");
    }
    Token column = requireIdentifier(tokens, cursor, "Expected a column name after '.'");
    return new ColumnRef(Optional.of(first.lexeme()), column.lexeme());
  }

  private static int findNextClause(List<Token> tokens, int start) {
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (is(token, "(")) depth++;
      else if (is(token, ")")) depth--;
      if (depth < 0) throw syntax("Unmatched closing parenthesis", token);
      if (depth == 0
          && (is(token, ";")
              || is(token, "LIMIT")
              || is(token, "HAVING")
              || (is(token, "GROUP")
                  && index + 1 < tokens.size()
                  && is(tokens.get(index + 1), "BY"))
              || (is(token, "ORDER")
                  && index + 1 < tokens.size()
                  && is(tokens.get(index + 1), "BY")))) return index;
    }
    if (depth != 0) throw syntax("Unclosed parenthesis", tokenAt(tokens, tokens.size()));
    return tokens.size();
  }

  private static int findTopLevel(List<Token> tokens, int start, String keyword) {
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (depth == 0 && is(token, keyword)) return index;
      if (is(token, "(")) depth++;
      else if (is(token, ")")) depth--;
    }
    return tokens.size();
  }

  private static int findJoinConditionEnd(List<Token> tokens, int start) {
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (is(token, "(")) depth++;
      else if (is(token, ")")) depth--;
      if (depth < 0) throw syntax("Unmatched closing parenthesis", token);
      if (depth == 0
          && (is(token, ",")
              || is(token, "JOIN")
              || is(token, "NATURAL")
              || is(token, "WHERE")
              || (is(token, "GROUP")
                  && index + 1 < tokens.size()
                  && is(tokens.get(index + 1), "BY"))
              || is(token, "HAVING")
              || is(token, "ORDER")
              || is(token, "LIMIT")
              || is(token, ";"))) return index;
    }
    if (depth != 0) throw syntax("Unclosed parenthesis", tokenAt(tokens, tokens.size()));
    return tokens.size();
  }

  private static Token requireIdentifier(List<Token> tokens, int[] cursor, String message) {
    Token token = tokenAt(tokens, cursor[0]);
    if (token == null || token.kind() != TokenKind.IDENTIFIER) throw syntax(message, token);
    cursor[0]++;
    return token;
  }

  private static void expect(List<Token> tokens, int[] cursor, String expected) {
    Token token = tokenAt(tokens, cursor[0]);
    if (token == null || !is(token, expected)) {
      throw syntax("Expected " + expected, token);
    }
    cursor[0]++;
  }

  private static void consumeTerminator(List<Token> tokens, int[] cursor) {
    if (peek(tokens, cursor, ";")) cursor[0]++;
  }

  private static boolean peek(List<Token> tokens, int[] cursor, String lexeme) {
    Token token = tokenAt(tokens, cursor[0]);
    return token != null && is(token, lexeme);
  }

  private static Token tokenAt(List<Token> tokens, int index) {
    return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
  }

  private static boolean is(Token token, String lexeme) {
    return token.lexeme().equalsIgnoreCase(lexeme);
  }

  private static String upper(Token token) {
    return token.lexeme().toUpperCase(Locale.ROOT);
  }

  private static DbmsException syntax(String message, Token token) {
    if (token == null) return new DbmsException(ErrorCode.SQL_SYNTAX, message + " at end of input");
    return new DbmsException(
        ErrorCode.SQL_SYNTAX,
        message + " at offset " + token.offset() + " near '" + token.lexeme() + "'");
  }
}
