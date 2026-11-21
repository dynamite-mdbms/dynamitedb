package dynamitedb.app.cli;

import dynamitedb.app.client.RemoteQueryClient;
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
    PrintWriter output = new PrintWriter(System.out, true);
    output.println("   ~~~*");
    output.println("      \\");
    output.println("  .---+------.");
    output.println(" /    TNT     \\");
    output.println("|_____________|  DynamiteDB");
    output.println("Local database: " + directory);
    output.println("End SQL with ';'; :read PATH runs a UTF-8 SQL file. Use :help.");
    output.flush();

    try (RemoteQueryClient client = new RemoteQueryClient(host, port)) {
      SqlStatementReader statements =
          new SqlStatementReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      ResultRenderer renderer = new ResultRenderer();
      while (true) {
        output.print(database + "> ");
        output.flush();
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
              command.length() == 5 ? "" : command.substring(6).strip(), client, renderer, output);
        } else if (normalized.startsWith(":")) {
          output.println("Unknown command. Use :help.");
        } else {
          try {
            renderer.print(client.execute(statement), output);
          } catch (DbmsException failure) {
            output.println("ERROR [" + failure.getCode() + "]: " + failure.getMessage());
          }
        }
      }
    }
  }

  static void printLocalHelp(PrintWriter output) {
    output.println("Commands: :help, :read PATH, :quit (or exit/quit)");
    output.println("Example: SELECT * FROM student;");
    output.println("Table name hints: student, course, enrollment.");
    output.println("CREATE TABLE, DROP TABLE, and CREATE VIEW are not executable in this version.");
    output.println("Use quoted or unquoted paths with spaces, for example: :read my SQL file.sql");
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
