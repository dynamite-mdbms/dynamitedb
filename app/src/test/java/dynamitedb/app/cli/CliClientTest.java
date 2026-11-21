package dynamitedb.app.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.mock.MockRuntime;
import dynamitedb.app.server.TcpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliClientTest {
  @Test
  void localEntryDisplaysBannerAndNormalizedDirectoryBeforeConnecting() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream previous = System.out;
    Path directory = Path.of("local-data", "..").toAbsolutePath();
    try {
      System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));

      assertThrows(IOException.class, () -> new CliClient().runLocal("127.0.0.1", 0, directory));
    } finally {
      System.setOut(previous);
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("DynamiteDB"));
    assertTrue(output.contains(directory.normalize().toString()));
    assertTrue(output.contains("End SQL with ';'"));
    assertTrue(output.contains(":read PATH"));
  }

  @Test
  void readScriptAcceptsPathsWithSpacesAndReportsEmptyFiles() throws Exception {
    Path file = Files.createTempFile("empty SQL ", ".sql");
    StringWriter text = new StringWriter();
    try {
      CliClient.readScript(file.toString(), null, new ResultRenderer(), new PrintWriter(text));
    } finally {
      Files.deleteIfExists(file);
    }

    assertTrue(text.toString().contains("Read 0 statement(s) from " + file));
  }

  @Test
  void readScriptAcceptsQuotedPathsWithSpaces() throws Exception {
    Path file = Files.createTempFile("quoted SQL ", ".sql");
    StringWriter text = new StringWriter();
    try {
      CliClient.readScript(
          '"' + file.toString() + '"', null, new ResultRenderer(), new PrintWriter(text));
    } finally {
      Files.deleteIfExists(file);
    }

    assertTrue(text.toString().contains("Read 0 statement(s) from " + file));
  }

  @Test
  void readScriptReportsMissingFilesWithPathAndStatementCount() throws Exception {
    StringWriter text = new StringWriter();
    Path directory = Files.createTempDirectory("missing sql file ");
    String path = directory.resolve("missing file.sql").toString();

    try {
      CliClient.readScript(path, null, new ResultRenderer(), new PrintWriter(text));
    } finally {
      Files.deleteIfExists(directory);
    }

    assertTrue(text.toString().contains(path));
    assertTrue(text.toString().contains("statement 1 after 0 completed statement(s)"));
    assertTrue(text.toString().contains("may already have committed"));
  }

  @Test
  void readScriptRejectsCliCommandsInsteadOfRunningThem() throws Exception {
    Path file = Files.createTempFile("commands", ".sql");
    Files.writeString(file, ":quit\n");
    StringWriter text = new StringWriter();
    try {
      CliClient.readScript(file.toString(), null, new ResultRenderer(), new PrintWriter(text));
    } finally {
      Files.deleteIfExists(file);
    }

    assertTrue(text.toString().contains("CLI commands are not allowed in SQL files"));
    assertTrue(text.toString().contains("statement 1 after 0 completed statement(s)"));
  }

  @Test
  void readScriptReportsIncompleteStatementAsTheFirstFailure() throws Exception {
    Path file = Files.createTempFile("incomplete", ".sql");
    Files.writeString(file, "SELECT 1");
    StringWriter text = new StringWriter();
    try {
      CliClient.readScript(file.toString(), null, new ResultRenderer(), new PrintWriter(text));
    } finally {
      Files.deleteIfExists(file);
    }

    assertTrue(text.toString().contains("statement 1 after 0 completed statement(s)"));
    assertTrue(text.toString().contains("ended before the statement was completed"));
  }

  @Test
  void localHelpOnlySuggestsSupportedCommandsAndDescribesFilePaths() {
    StringWriter text = new StringWriter();

    CliClient.printLocalHelp(new PrintWriter(text));

    assertTrue(text.toString().contains("SELECT * FROM student;"));
    assertTrue(text.toString().contains("student, course, enrollment"));
    assertTrue(text.toString().contains("CREATE TABLE and DROP TABLE outside a transaction"));
    assertTrue(text.toString().contains("CREATE VIEW is not supported"));
    assertTrue(text.toString().contains("quoted or unquoted paths with spaces"));
    assertTrue(!text.toString().contains("INSERT INTO"));
  }

  @Test
  void oversizedLocalRequestDoesNotExitShell() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream previousOut = System.out;
    java.io.InputStream previousIn = System.in;
    try (TcpServer server = new TcpServer(new MockRuntime().processor(), 0)) {
      server.start();
      System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
      System.setIn(
          new java.io.ByteArrayInputStream(
              ("SELECT '" + "x".repeat(1_048_576) + "';\n:help\n:quit\n")
                  .getBytes(StandardCharsets.UTF_8)));

      new CliClient().runLocal("127.0.0.1", server.port(), Path.of("local-data"));
    } finally {
      System.setOut(previousOut);
      System.setIn(previousIn);
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("ERROR: SQL exceeds the 1 MiB request limit"));
    assertTrue(output.contains("Commands: :help"));
  }

  @Test
  void bareReadCommandShowsUsage() {
    StringWriter text = new StringWriter();

    CliClient.readScript("", null, new ResultRenderer(), new PrintWriter(text));

    assertTrue(text.toString().contains("Usage: :read PATH"));
  }

  @Test
  void readScriptRejectsDirectoriesAsNonregularFiles() throws Exception {
    Path directory = Files.createTempDirectory("sql directory ");
    StringWriter text = new StringWriter();
    try {
      CliClient.readScript(directory.toString(), null, new ResultRenderer(), new PrintWriter(text));
    } finally {
      Files.deleteIfExists(directory);
    }

    assertTrue(text.toString().contains("not a regular file"));
    assertTrue(text.toString().contains("statement 1 after 0 completed statement(s)"));
  }
}
