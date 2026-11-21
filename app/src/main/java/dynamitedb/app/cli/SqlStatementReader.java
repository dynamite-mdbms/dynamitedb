package dynamitedb.app.cli;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;

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
    int contentStart = -1;
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
      } else if (current == ':' && contentStart < 0) {
        // Shell command arguments are paths or text, not SQL tokens.
        int start = sql.length() - 1;
        while ((value = input.read()) != -1 && value != '\n') sql.append((char) value);
        return sql.substring(start).strip();
      } else if (current == '\'' || current == '"') {
        if (contentStart < 0) contentStart = sql.length() - 1;
        state = current == '\'' ? State.SINGLE_QUOTE : State.DOUBLE_QUOTE;
      } else if (current == ';') {
        if (contentStart >= 0) {
          String command = sql.substring(contentStart, sql.length() - 1).strip();
          return isExit(command) ? command : sql.toString().strip();
        }
        sql.setLength(0);
      } else if (current == '\n' && contentStart >= 0) {
        String command = sql.substring(contentStart).strip();
        if (isExit(command)) return command;
      } else if (contentStart < 0 && !Character.isWhitespace(current)) {
        contentStart = sql.length() - 1;
      }
    }
    if (contentStart >= 0 && state == State.NORMAL) {
      String command = sql.substring(contentStart).strip();
      if (isExit(command)) return command;
    }
    if (contentStart < 0 && state != State.BLOCK_COMMENT) return null;
    throw new IOException("Input ended before the statement was completed with ';'");
  }

  private boolean consume(char expected) throws IOException {
    int next = input.read();
    if (next == expected) return true;
    if (next != -1) input.unread(next);
    return false;
  }

  private static boolean isExit(String value) {
    return value.equalsIgnoreCase("exit") || value.equalsIgnoreCase("quit");
  }
}
