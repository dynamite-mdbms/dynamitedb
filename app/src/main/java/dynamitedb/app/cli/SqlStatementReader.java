package dynamitedb.app.cli;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.Locale;

/** Reads multiline statements without splitting semicolons inside quotes or comments. */
public final class SqlStatementReader {
  private enum State {
    NORMAL,
    SINGLE_QUOTE,
    DOUBLE_QUOTE,
    LINE_COMMENT,
    BLOCK_COMMENT
  }

  private final PushbackReader input;

  public SqlStatementReader(Reader input) {
    this.input = new PushbackReader(input, 1);
  }

  public String next() throws IOException {
    StringBuilder sql = new StringBuilder();
    State state = State.NORMAL;
    boolean hasSql = false;
    int value;
    while ((value = input.read()) != -1) {
      char current = (char) value;
      sql.append(current);
      if (state == State.LINE_COMMENT) {
        if (current == '\n') state = State.NORMAL;
        continue;
      }
      if (state == State.BLOCK_COMMENT) {
        if (current == '*' && consume('/')) {
          sql.append('/');
          state = State.NORMAL;
        }
        continue;
      }
      if (state == State.SINGLE_QUOTE || state == State.DOUBLE_QUOTE) {
        char quote = state == State.SINGLE_QUOTE ? '\'' : '"';
        if (current == quote) {
          if (consume(quote)) sql.append(quote);
          else state = State.NORMAL;
        }
        continue;
      }
      if (current == '-' && consume('-')) {
        sql.append('-');
        state = State.LINE_COMMENT;
      } else if (current == '/' && consume('*')) {
        sql.append('*');
        state = State.BLOCK_COMMENT;
      } else if (current == '\'' || current == '"') {
        hasSql = true;
        state = current == '\'' ? State.SINGLE_QUOTE : State.DOUBLE_QUOTE;
      } else if (current == ';') {
        if (hasSql) return sql.toString().strip();
        sql.setLength(0);
      } else if (current == '\n' && isCommand(sql.toString())) {
        return sql.toString().strip();
      } else if (!Character.isWhitespace(current)) {
        hasSql = true;
      }
    }
    String remaining = sql.toString().strip();
    if (state == State.NORMAL && isCommand(remaining)) return remaining;
    if (!hasSql && state != State.BLOCK_COMMENT) return null;
    throw new IOException("Input ended before the statement was completed with ';'");
  }

  private boolean consume(char expected) throws IOException {
    int next = input.read();
    if (next == expected) return true;
    if (next != -1) input.unread(next);
    return false;
  }

  private static boolean isCommand(String value) {
    String command = value.strip().toLowerCase(Locale.ROOT);
    return command.equals("exit") || command.equals("quit") || command.startsWith(":");
  }
}
