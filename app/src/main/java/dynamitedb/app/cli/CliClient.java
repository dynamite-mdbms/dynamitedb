package dynamitedb.app.cli;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.app.protocol.WireProtocol;
import dynamitedb.contracts.error.DbmsException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

public final class CliClient {
  public void run(String host, int port) throws IOException {
    run(
        host,
        port,
        new InputStreamReader(System.in, StandardCharsets.UTF_8),
        new PrintWriter(System.out, true),
        true);
  }

  public void runLocal(String host, int port, Path dataDirectory) throws IOException {
    Path directory = dataDirectory.toAbsolutePath().normalize();
    String database =
        directory.getFileName() == null ? directory.toString() : directory.getFileName().toString();
    boolean hasConsole = System.console() != null && !"dumb".equals(System.getenv("TERM"));
    try (Terminal terminal = hasConsole ? TerminalBuilder.builder().system(true).build() : null) {
      boolean interactive =
          terminal != null
              && !Terminal.TYPE_DUMB.equals(terminal.getType())
              && !Terminal.TYPE_DUMB_COLOR.equals(terminal.getType());
      PrintWriter output = interactive ? terminal.writer() : new PrintWriter(System.out, true);
      Reader input;
      if (interactive) {
        if (!awaitSplashKey(directory, terminal)) return;
        LineReader lineReader = createLineReader(terminal);
        input =
            new Reader() {
              private String line = "";
              private int position;

              @Override
              public int read(char[] target, int offset, int length) {
                if (length == 0) return 0;
                if (position == line.length()) {
                  line = lineReader.readLine(database + "> ") + "\n";
                  position = 0;
                }
                int count = Math.min(length, line.length() - position);
                line.getChars(position, position + count, target, offset);
                position += count;
                return count;
              }

              @Override
              public void close() {}
            };
      } else {
        printCompactBanner(output);
        output.println("Local database: " + directory);
        output.println("End SQL with ';'; :read PATH runs a UTF-8 SQL file. Use :help.");
        input = new InputStreamReader(System.in, StandardCharsets.UTF_8);
      }
      SqlStatementReader statements = new SqlStatementReader(input);
      try (RemoteQueryClient client = new RemoteQueryClient(host, port)) {
        ResultRenderer renderer = new ResultRenderer();
        while (true) {
          if (!interactive) {
            output.print(database + "> ");
            output.flush();
          }
          String statement = statements.next();
          if (statement == null) return;
          String command = statement.replaceFirst(";\\s*$", "").strip();
          String normalized = command.toLowerCase(Locale.ROOT);
          if (normalized.equals("exit") || normalized.equals("quit") || normalized.equals(":quit"))
            return;
          if (normalized.equals(":help")) {
            printLocalHelp(output);
          } else if (normalized.equals(":read") || normalized.startsWith(":read ")) {
            readScript(
                command.length() == 5 ? "" : command.substring(6).strip(),
                client,
                renderer,
                output);
          } else if (normalized.startsWith(":")) {
            output.println("Unknown command. Use :help.");
          } else {
            if (statement.getBytes(StandardCharsets.UTF_8).length > WireProtocol.MAX_SQL_BYTES) {
              output.println("ERROR: SQL exceeds the 1 MiB request limit");
              continue;
            }
            try {
              renderer.print(client.execute(statement), output);
            } catch (DbmsException failure) {
              output.println("ERROR [" + failure.getCode() + "]: " + failure.getMessage());
            }
          }
        }
      } catch (EndOfFileException | UserInterruptException end) {
        // Exit without submitting any partially entered interactive statement.
      }
    }
  }

  static LineReader createLineReader(Terminal terminal) {
    LineReader editor =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            // SQL quoting and statement boundaries belong to SqlStatementReader.
            .parser(new DefaultParser().quoteChars(null).escapeChars(null))
            .build();
    var keys = editor.getKeyMaps().get(LineReader.MAIN);
    // Keep terminfo bindings (e.g. xterm SS3), but accept normal cursor mode too.
    keys.bindIfNotBound(new Reference(LineReader.UP_LINE_OR_HISTORY), "\033[A");
    keys.bindIfNotBound(new Reference(LineReader.DOWN_LINE_OR_HISTORY), "\033[B");
    return editor;
  }

  private static boolean awaitSplashKey(Path directory, Terminal terminal) throws IOException {
    Attributes original = terminal.enterRawMode();
    try {
      // JLine raw mode leaves signal generation enabled; handle Ctrl+C as input here.
      Attributes raw = terminal.getAttributes();
      raw.setLocalFlag(Attributes.LocalFlag.ISIG, false);
      terminal.setAttributes(raw);
      terminal.puts(Capability.clear_screen);
      PrintWriter output = terminal.writer();
      Integer colors = terminal.getNumericCapability(Capability.max_colors);
      boolean color = System.getenv("NO_COLOR") == null && colors != null && colors >= 8;
      if (terminal.getWidth() >= 48) {
        String gold = color ? "\033[33m" : "";
        String red = color ? "\033[31m" : "";
        String cyan = color ? "\033[36m" : "";
        String reset = color ? "\033[0m" : "";
        output.print(gold);
        output.println("                   \\ | /");
        output.println("                 --  *  --");
        output.println("                   / | \\~~~.");
        output.print(red);
        output.println("                            |");
        output.println("      .=====================+==========.");
        output.println("     /                                  \\");
        output.println("    |      [ T ]    [ N ]    [ T ]       |");
        output.println("    |____________________________________|");
        output.print(cyan);
        output.println("             D Y N A M I T E D B" + reset);
      } else {
        printCompactBanner(output);
      }
      output.println();
      output.println("Local database: " + directory);
      output.println("Press any key to continue");
      output.flush();
      NonBlockingReader input = terminal.reader();
      int key = input.read();
      if (key == 27) {
        // Arrow and function keys send an escape sequence, not a single byte.
        do {
          key = input.read(100);
        } while (key == 27);
        if (key == '[' || key == 'O') {
          int prefix = key;
          key = input.read(100);
          if (prefix == '[' && key == '[') key = input.read(100); // Linux console F1–F5.
          while (key >= 0x20 && key <= 0x3f) {
            key = input.read(100);
          }
        }
      }
      return key != 3 && key != 4 && key != NonBlockingReader.EOF;
    } finally {
      terminal.setAttributes(original);
    }
  }

  private static void printCompactBanner(PrintWriter output) {
    output.println("   ~~~*");
    output.println("      \\");
    output.println("  .---+------.");
    output.println(" /    TNT     \\");
    output.println("|_____________|  DynamiteDB");
  }

  static void printLocalHelp(PrintWriter output) {
    output.print(
        """
        Commands: :help, :read PATH, :quit (or exit/quit)

        SQL
          End statements with ';', for example: SELECT * FROM student;
          Built-in tables: student, course, enrollment.
          Run CREATE TABLE and DROP TABLE outside a transaction.
          CREATE VIEW is not supported.

        Scripts and storage
          :read PATH runs a compatible UTF-8 SQL script, relative to the current directory.
          It stops at the first error; earlier statements may already have committed.
          Use quoted or unquoted paths with spaces, e.g. :read my SQL file.sql
          --data-dir PATH selects the database folder at startup (default: ./data).

        Interactive keys
          Up/Down browse this session's command history. Paste SQL, then press Enter.
          Ctrl+C exits; Ctrl+D exits on an empty line.
        """);
    output.flush();
  }

  static void readScript(
      String path, RemoteQueryClient client, ResultRenderer renderer, PrintWriter output) {
    if (path.length() >= 2
        && ((path.startsWith("\"") && path.endsWith("\""))
            || (path.startsWith("'") && path.endsWith("'")))) {
      path = path.substring(1, path.length() - 1);
    }
    if (path.isEmpty()) {
      output.println("Usage: :read PATH");
      return;
    }
    int completed = 0;
    int statementNumber = 1;
    try {
      Path file = Path.of(path);
      if (!Files.isRegularFile(file)) throw new IOException("not a regular file");
      try (Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        SqlStatementReader statements = new SqlStatementReader(input);
        String statement;
        while ((statement = statements.next()) != null) {
          String command = statement.replaceFirst(";\\s*$", "").strip().toLowerCase(Locale.ROOT);
          if (command.startsWith(":") || command.equals("exit") || command.equals("quit")) {
            throw new IOException("CLI commands are not allowed in SQL files");
          }
          renderer.print(client.execute(statement), output);
          completed++;
          statementNumber++;
        }
      }
      output.println("Read " + completed + " statement(s) from " + path);
    } catch (IOException | DbmsException | InvalidPathException failure) {
      output.println(
          "ERROR reading "
              + path
              + " at statement "
              + statementNumber
              + " after "
              + completed
              + " completed statement(s); earlier statements may already have committed: "
              + failure.getMessage());
    }
    output.flush();
  }

  public void run(String host, int port, Reader input, PrintWriter output, boolean prompts)
      throws IOException {
    try (RemoteQueryClient client = new RemoteQueryClient(host, port)) {
      if (prompts) output.println("Connected. End SQL with ';'; use exit or quit to leave.");
      SqlStatementReader statements = new SqlStatementReader(input);
      ResultRenderer renderer = new ResultRenderer();
      while (true) {
        if (prompts) {
          output.print("db> ");
          output.flush();
        }
        String sql = statements.next();
        if (sql == null) break;
        String command = sql.replaceFirst(";\\s*$", "").strip().toLowerCase(Locale.ROOT);
        if (command.equals("exit") || command.equals("quit") || command.equals(":quit")) break;
        try {
          renderer.print(client.execute(sql), output);
        } catch (DbmsException failure) {
          output.println("ERROR [" + failure.getCode() + "]: " + failure.getMessage());
          output.flush();
        }
      }
    }
  }
}
