package dynamitedb.app.driver;

import dynamitedb.app.cli.ResultRenderer;
import dynamitedb.app.cli.SqlStatementReader;
import dynamitedb.contracts.api.QueryProcessor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.qp.driver.QueryProcessorTestDriver;
import dynamitedb.qp.driver.QueryProcessorTestDriver.Scenario;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Interactive standalone driver; does not start a server or require the main CLI. */
public final class DriverCli {
  private final QueryProcessor processor;

  public DriverCli(QueryProcessor processor) {
    this.processor = processor;
  }

  public TestReport runDemoSuite() {
    return new QueryProcessorTestDriver(processor)
        .runScenarios(
            List.of(
                success("Scan: 3 rows", "SELECT * FROM demo;", result -> result.rows().size() == 3),
                success(
                    "Empty: retain 2 headers",
                    "SELECT * FROM empty;",
                    result -> result.rows().isEmpty() && result.schema().columns().size() == 2),
                success(
                    "Filter: 1 row",
                    "SELECT * FROM demo WHERE id = 1;",
                    result -> result.rows().size() == 1),
                success(
                    "Sort: descending",
                    "SELECT * FROM demo ORDER BY id DESC;",
                    result ->
                        result
                            .rows()
                            .getFirst()
                            .values()
                            .getFirst()
                            .equals(new dynamitedb.contracts.storage.IntValue(3))),
                success(
                    "Join with empty: 0 rows",
                    "SELECT * FROM demo, empty;",
                    result -> result.rows().isEmpty()),
                success(
                    "Insert: 1 affected",
                    "INSERT INTO demo VALUES (4, 'Dina');",
                    result -> result.affectedRows() == 1),
                success(
                    "Update: 1 affected",
                    "UPDATE demo SET name = 'Dina';",
                    result -> result.affectedRows() == 1),
                success(
                    "Delete: 1 affected",
                    "DELETE FROM demo WHERE id = 1;",
                    result -> result.affectedRows() == 1),
                success(
                    "Create index",
                    "CREATE INDEX idx_demo_id ON demo(id);",
                    result -> result.message().equals("CREATE INDEX")),
                success("Begin", "BEGIN;", result -> result.message().equals("BEGIN")),
                success("Commit", "COMMIT;", result -> result.message().equals("COMMIT")),
                success("Begin again", "BEGIN TRANSACTION;", result -> true),
                success("Abort", "ABORT;", result -> result.message().equals("ABORT")),
                new Scenario(
                    "Invalid SQL", "nonsense;", Optional.of(ErrorCode.SQL_SYNTAX), result -> false),
                new Scenario(
                    "Execution failure",
                    "SELECT * FROM fail;",
                    Optional.of(ErrorCode.STORAGE_FAILURE),
                    result -> false),
                success(
                    "Continue after error",
                    "SELECT * FROM demo;",
                    result -> result.rows().size() == 3)));
  }

  private static Scenario success(
      String name,
      String sql,
      java.util.function.Predicate<dynamitedb.contracts.query.QueryResult> check) {
    return new Scenario(name, sql, Optional.empty(), check);
  }

  public TestReport runFile(Path path) throws IOException {
    List<String> queries = new ArrayList<>();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      SqlStatementReader statements = new SqlStatementReader(reader);
      String sql;
      while ((sql = statements.next()) != null) queries.add(sql);
    }
    return new QueryProcessorTestDriver(processor).runSuite(queries);
  }

  public void run(Reader input, PrintWriter output) throws IOException {
    output.println(
        "Standalone QP driver (mock fixtures). :suite, :file <path>, :quit, or SQL ending ';'.");
    SessionId session = processor.openSession("interactive-driver");
    try {
      SqlStatementReader statements = new SqlStatementReader(input);
      ResultRenderer renderer = new ResultRenderer();
      while (true) {
        output.print("driver> ");
        output.flush();
        String sql = statements.next();
        if (sql == null
            || sql.equalsIgnoreCase(":quit")
            || sql.equalsIgnoreCase("exit")
            || sql.equalsIgnoreCase("quit")) break;
        try {
          if (sql.equals(":suite")) printReport(runDemoSuite(), output);
          else if (sql.startsWith(":file "))
            printReport(runFile(Path.of(sql.substring(6).strip())), output);
          else renderer.print(processor.execute(session, sql), output);
        } catch (DbmsException failure) {
          output.println("ERROR [" + failure.getCode() + "]: " + failure.getMessage());
        } catch (IOException failure) {
          output.println("ERROR: " + failure.getMessage());
        }
      }
    } finally {
      processor.closeSession(session);
    }
  }

  public static void printReport(TestReport report, PrintWriter output) {
    report
        .results()
        .forEach(
            result -> output.println((result.passed() ? "PASS " : "FAIL ") + result.message()));
    long passed = report.results().stream().filter(result -> result.passed()).count();
    output.println(passed + "/" + report.results().size() + " passed");
    output.flush();
  }
}
