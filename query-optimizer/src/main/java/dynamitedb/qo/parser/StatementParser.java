package dynamitedb.qo.parser;

import static dynamitedb.qo.parser.ExpressionParser.syntax;

import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.CreateTableStatement;
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.ForeignKey;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionStatement;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Mengenali statement non-SELECT dari token (QO-05, QO-06, QO-17). SELECT ditangani SqlParser. */
public class StatementParser {

  private final ExpressionParser expressions;

  public StatementParser() {
    this(new ExpressionParser());
  }

  public StatementParser(ExpressionParser expressions) {
    this.expressions = expressions;
  }

  public Statement parseStatement(List<Token> tokens) {
    if (tokens == null || tokens.isEmpty()) throw syntax("Expected an SQL statement", null);
    // Satu titik koma terminal boleh ada; token setelah titik koma lain tetap ditolak.
    int end = tokens.size();
    if (is(tokens.get(end - 1), ";")) end--;
    List<Token> body = tokens.subList(0, end);
    if (body.isEmpty()) throw syntax("Expected an SQL statement", tokens.get(0));

    Token first = body.get(0);
    int[] cursor = {1};
    Statement statement =
        switch (first.lexeme().toUpperCase(Locale.ROOT)) {
          case "INSERT" -> parseInsert(body, cursor);
          case "UPDATE" -> parseUpdate(body, cursor);
          case "DELETE" -> parseDelete(body, cursor);
          case "BEGIN" -> {
            if (peek(body, cursor, "TRANSACTION")) cursor[0]++;
            yield new TransactionStatement(TransactionCommand.BEGIN);
          }
          case "COMMIT" -> new TransactionStatement(TransactionCommand.COMMIT);
          case "ABORT" -> new TransactionStatement(TransactionCommand.ABORT);
          case "CREATE" -> {
            if (peek(body, cursor, "TABLE")) yield parseCreateTable(body, cursor);
            if (!peek(body, cursor, "INDEX")) {
              throw syntax("Expected INDEX or TABLE", tokenAt(body, cursor[0]));
            }
            yield parseCreateIndex(body, cursor);
          }
          default -> throw syntax("Expected a supported statement keyword", first);
        };
    if (cursor[0] < body.size()) {
      throw syntax(
          "Unexpected token after " + first.lexeme().toUpperCase(Locale.ROOT) + " statement",
          body.get(cursor[0]));
    }
    return statement;
  }

  /** INSERT INTO table VALUES (expr, ...) */
  private InsertStatement parseInsert(List<Token> tokens, int[] cursor) {
    expect(tokens, cursor, "INTO");
    String table = requireIdentifier(tokens, cursor, "Expected a table name").lexeme();
    expect(tokens, cursor, "VALUES");
    Token open = tokenAt(tokens, cursor[0]);
    expect(tokens, cursor, "(");

    List<Expression> values = new ArrayList<>();
    int depth = 0;
    int start = cursor[0];
    for (int index = start; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (is(token, "(")) depth++;
      else if (is(token, ")") && depth > 0) depth--;
      else if (depth == 0 && (is(token, ",") || is(token, ")"))) {
        values.add(parseSegment(tokens, start, index));
        start = index + 1;
        if (is(token, ")")) {
          cursor[0] = start;
          return new InsertStatement(table, values);
        }
      }
    }
    throw syntax("Expected ')' to close VALUES list opened at offset " + open.offset(), null);
  }

  /** UPDATE table SET column = expr [WHERE condition] */
  private UpdateStatement parseUpdate(List<Token> tokens, int[] cursor) {
    String table = requireIdentifier(tokens, cursor, "Expected a table name").lexeme();
    expect(tokens, cursor, "SET");
    ColumnRef target = parseColumn(tokens, cursor);
    expect(tokens, cursor, "=");
    int where = indexOf(tokens, cursor[0], "WHERE");
    Expression value = parseSegment(tokens, cursor[0], where);
    cursor[0] = where;
    return new UpdateStatement(table, target, value, parseOptionalWhere(tokens, cursor));
  }

  /** DELETE FROM table [WHERE condition] */
  private DeleteStatement parseDelete(List<Token> tokens, int[] cursor) {
    expect(tokens, cursor, "FROM");
    String table = requireIdentifier(tokens, cursor, "Expected a table name").lexeme();
    return new DeleteStatement(table, parseOptionalWhere(tokens, cursor));
  }

  /** CREATE INDEX name ON table(column) [USING HASH | USING B_PLUS_TREE]; default HASH. */
  private static CreateIndexStatement parseCreateIndex(List<Token> tokens, int[] cursor) {
    expect(tokens, cursor, "INDEX");
    String index = requireIdentifier(tokens, cursor, "Expected an index name").lexeme();
    expect(tokens, cursor, "ON");
    String table = requireIdentifier(tokens, cursor, "Expected a table name").lexeme();
    expect(tokens, cursor, "(");
    String column = requireIdentifier(tokens, cursor, "Expected a column name").lexeme();
    expect(tokens, cursor, ")");

    IndexKind kind = IndexKind.HASH;
    if (peek(tokens, cursor, "USING")) {
      cursor[0]++;
      Token token = tokenAt(tokens, cursor[0]);
      if (token != null && is(token, "HASH")) kind = IndexKind.HASH;
      else if (token != null && is(token, "B_PLUS_TREE")) kind = IndexKind.B_PLUS_TREE;
      else throw syntax("Expected HASH or B_PLUS_TREE after USING", token);
      cursor[0]++;
    }
    return new CreateIndexStatement(index, table, column, kind);
  }

  /**
   * CREATE TABLE t (col type [PRIMARY KEY], ... [, PRIMARY KEY (col)] [, FOREIGN KEY (col)
   * REFERENCES t2(col)]). Keys are single-column; the binder rejects more than one PRIMARY KEY.
   */
  private static CreateTableStatement parseCreateTable(List<Token> tokens, int[] cursor) {
    expect(tokens, cursor, "TABLE");
    String table = requireIdentifier(tokens, cursor, "Expected a table name").lexeme();
    expect(tokens, cursor, "(");
    List<Column> columns = new ArrayList<>();
    List<String> primaryKey = new ArrayList<>();
    List<ForeignKey> foreignKeys = new ArrayList<>();
    do {
      if (accept(tokens, cursor, "PRIMARY")) {
        expect(tokens, cursor, "KEY");
        primaryKey.add(parenthesizedColumn(tokens, cursor));
      } else if (accept(tokens, cursor, "FOREIGN")) {
        expect(tokens, cursor, "KEY");
        String column = parenthesizedColumn(tokens, cursor);
        expect(tokens, cursor, "REFERENCES");
        String target = requireIdentifier(tokens, cursor, "Expected a table name").lexeme();
        foreignKeys.add(new ForeignKey(column, target, parenthesizedColumn(tokens, cursor)));
      } else {
        Column column = parseColumnDefinition(table, tokens, cursor);
        columns.add(column);
        if (accept(tokens, cursor, "PRIMARY")) {
          expect(tokens, cursor, "KEY");
          primaryKey.add(column.name());
        }
      }
    } while (accept(tokens, cursor, ","));
    expect(tokens, cursor, ")");
    return new CreateTableStatement(table, columns, primaryKey, foreignKeys);
  }

  /** col INTEGER | FLOAT | CHAR(n) | VARCHAR(n); nullability is settled by the binder. */
  private static Column parseColumnDefinition(String table, List<Token> tokens, int[] cursor) {
    String name = requireIdentifier(tokens, cursor, "Expected a column name").lexeme();
    Token typeName = tokenAt(tokens, cursor[0]);
    DataType type =
        Arrays.stream(DataType.values())
            .filter(t -> typeName != null && is(typeName, t.name()))
            .findFirst()
            .orElseThrow(
                () ->
                    syntax(
                        "Expected a column type INTEGER, FLOAT, CHAR(n), or VARCHAR(n)", typeName));
    cursor[0]++;
    int length = 0;
    if (type == DataType.CHAR || type == DataType.VARCHAR) {
      expect(tokens, cursor, "(");
      Token size = tokenAt(tokens, cursor[0]);
      // Up to 9 digits always fits an int; the binder checks the allowed range.
      if (size == null || !size.lexeme().matches("[0-9]{1,9}")) {
        throw syntax("Expected an integer length for " + type, size);
      }
      length = Integer.parseInt(size.lexeme());
      cursor[0]++;
      expect(tokens, cursor, ")");
    }
    return new Column(table, name, type, length, true);
  }

  private static String parenthesizedColumn(List<Token> tokens, int[] cursor) {
    expect(tokens, cursor, "(");
    String column = requireIdentifier(tokens, cursor, "Expected a column name").lexeme();
    expect(tokens, cursor, ")");
    return column;
  }

  /** Mengonsumsi sisa token sebagai kondisi WHERE, jika ada. */
  private Optional<Expression> parseOptionalWhere(List<Token> tokens, int[] cursor) {
    if (cursor[0] >= tokens.size()) return Optional.empty();
    expect(tokens, cursor, "WHERE");
    Expression predicate = parseSegment(tokens, cursor[0], tokens.size());
    cursor[0] = tokens.size();
    return Optional.of(predicate);
  }

  private Expression parseSegment(List<Token> tokens, int start, int end) {
    if (start == end) throw syntax("Expected expression", tokenAt(tokens, start));
    return expressions.parseExpression(tokens.subList(start, end));
  }

  private static ColumnRef parseColumn(List<Token> tokens, int[] cursor) {
    Token first = requireIdentifier(tokens, cursor, "Expected a column name");
    if (!peek(tokens, cursor, ".")) return new ColumnRef(Optional.empty(), first.lexeme());
    cursor[0]++;
    Token column = requireIdentifier(tokens, cursor, "Expected a column name after '.'");
    return new ColumnRef(Optional.of(first.lexeme()), column.lexeme());
  }

  private static int indexOf(List<Token> tokens, int start, String lexeme) {
    for (int index = start; index < tokens.size(); index++) {
      if (is(tokens.get(index), lexeme)) return index;
    }
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
    if (token == null || !is(token, expected)) throw syntax("Expected " + expected, token);
    cursor[0]++;
  }

  private static boolean accept(List<Token> tokens, int[] cursor, String lexeme) {
    if (!peek(tokens, cursor, lexeme)) return false;
    cursor[0]++;
    return true;
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
}
