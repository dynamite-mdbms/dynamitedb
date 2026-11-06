package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.cli.CliClient;
import dynamitedb.app.cli.ResultRenderer;
import dynamitedb.app.driver.DriverCli;
import dynamitedb.app.mock.MockRuntime;
import dynamitedb.app.server.TcpServer;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.qp.driver.QueryProcessorTestDriver;
import dynamitedb.qp.driver.QueryProcessorTestDriver.Scenario;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QpDriverTest {
  @Test
  void suiteChecksActualOperatorsAndCanBeRepeated() {
    DriverCli driver = new DriverCli(new MockRuntime().processor());
    for (int run = 0; run < 2; run++) {
      var report = driver.runDemoSuite();
      assertEquals(24, report.results().size());
      assertTrue(report.passed(), report.results().toString());
    }
  }

  @Test
  void incorrectResultsFailAndExpectedErrorsPass() {
    var driver = new QueryProcessorTestDriver(new MockRuntime().processor());
    var report =
        driver.runScenarios(
            List.of(
                new Scenario(
                    "Expected 99 rows",
                    "SELECT * FROM demo;",
                    Optional.empty(),
                    result -> result.rows().size() == 99),
                new Scenario(
                    "Expected syntax error",
                    "nonsense;",
                    Optional.of(ErrorCode.SQL_SYNTAX),
                    result -> false),
                new Scenario(
                    "Continue: expected 3 rows",
                    "SELECT * FROM demo;",
                    Optional.empty(),
                    result -> result.rows().size() == 3)));
    assertFalse(report.results().get(0).passed());
    assertTrue(report.results().get(1).passed());
    assertTrue(report.results().get(2).passed());
    assertTrue(report.results().get(0).message().contains("values="));
  }

  @Test
  void scriptReportsExecutionChecksExplicitly(@TempDir Path directory) throws Exception {
    Path script = directory.resolve("queries.sql");
    Files.writeString(script, "SELECT *\nFROM demo;\nSELECT * FROM empty;");
    var report = new DriverCli(new MockRuntime().processor()).runFile(script);
    assertTrue(report.passed());
    assertEquals(2, report.results().size());
    assertTrue(report.results().getFirst().message().startsWith("Execution only:"));
  }

  @Test
  void cliAcceptsMultilineSqlAndContinuesAfterErrors() throws Exception {
    StringWriter text = new StringWriter();
    try (TcpServer server = new TcpServer(new MockRuntime().processor(), 0)) {
      server.start();
      new CliClient()
          .run(
              "127.0.0.1",
              server.port(),
              new StringReader("SELECT *\nFROM demo;\nnonsense;\nSELECT * FROM empty;\nexit;\n"),
              new PrintWriter(text),
              false);
    }
    String output = text.toString();
    assertTrue(output.contains("3 row(s)"));
    assertTrue(output.contains("Ishak 東京 😀"));
    assertTrue(output.contains("NULL"));
    assertTrue(output.contains("ERROR [SQL_SYNTAX]"));
    assertTrue(output.contains("0 row(s)"));
    assertFalse(output.contains("Exception"));
  }

  @Test
  void leavingInteractiveDriverAbortsItsTransaction() throws Exception {
    var processor = new MockRuntime().processor();
    StringWriter output = new StringWriter();
    new DriverCli(processor)
        .run(
            new StringReader(
                "BEGIN; INSERT INTO demo VALUES (4, 'Dina'); quit; SELECT * FROM fail;"),
            new PrintWriter(output));
    assertFalse(output.toString().contains("ERROR"));
    var observer = processor.openSession("observer");
    try {
      assertTrue(processor.execute(observer, "SELECT * FROM demo WHERE id = 4;").rows().isEmpty());
    } finally {
      processor.closeSession(observer);
    }
  }

  @Test
  void engineAffectedRowMessageIsPrintedOnce() {
    var processor = new MockRuntime().processor();
    var session = processor.openSession("test");
    try {
      var result = processor.execute(session, "INSERT INTO demo VALUES (4, 'Dina');");
      StringWriter output = new StringWriter();
      new ResultRenderer().print(result, new PrintWriter(output));
      assertEquals(
          1, output.toString().lines().filter(line -> line.equals("1 row(s) affected")).count());
    } finally {
      processor.closeSession(session);
    }
  }
}
