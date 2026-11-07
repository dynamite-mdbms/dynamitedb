package dynamitedb.app.driver;

import dynamitedb.app.cli.ResultRenderer;
import dynamitedb.app.cli.SqlStatementReader;
import dynamitedb.contracts.api.QueryProcessor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.TextValue;
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
                success(
                    "Scan: ids 1, 2, 3",
                    "SELECT * FROM demo;",
                    result ->
                        result.schema().columns().size() == 2
                            && ids(result).equals(List.of(1, 2, 3))),
                success(
                    "Empty: retain id/name headers",
                    "SELECT * FROM empty;",
                    result ->
                        result.rows().isEmpty()
                            && result.schema().columns().stream()
                                .map(column -> column.name())
                                .toList()
                                .equals(List.of("id", "name"))),
                success(
                    "Project: id only",
                    "SELECT id FROM demo;",
                    result ->
                        result.schema().columns().size() == 1
                            && result
                                .rows()
                                .equals(
                                    List.of(
                                        new Row(List.of(new IntValue(1))),
                                        new Row(List.of(new IntValue(2))),
                                        new Row(List.of(new IntValue(3)))))),
                success(
                    "Filter: only Ayu",
                    "SELECT * FROM demo WHERE id = 1;",
                    result -> result.rows().equals(List.of(demoRow(1, "Ayu")))),
                success("Begin", "BEGIN;", result -> result.message().equals("BEGIN")),
                success(
                    "Insert in transaction: 1 affected",
                    "INSERT INTO demo VALUES (4, 'Dina');",
                    result -> result.affectedRows() == 1),
                success(
                    "Read own insert: Dina",
                    "SELECT * FROM demo WHERE id = 4;",
                    result -> result.rows().equals(List.of(demoRow(4, "Dina")))),
                success("Abort", "ABORT;", result -> result.message().equals("ABORT")),
                success(
                    "Aborted insert: 0 rows",
                    "SELECT * FROM demo WHERE id = 4;",
                    result -> result.rows().isEmpty()),
                success(
                    "Auto-commit insert: 1 affected",
                    "INSERT INTO demo VALUES (4, 'Dina');",
                    result -> result.affectedRows() == 1),
                success(
                    "Committed insert is visible",
                    "SELECT * FROM demo WHERE id = 4;",
                    result -> result.rows().equals(List.of(demoRow(4, "Dina")))),
                success(
                    "Begin update",
                    "BEGIN TRANSACTION;",
                    result -> result.message().equals("BEGIN")),
                success(
                    "Update: only id 1 affected",
                    "UPDATE demo SET name = 'Dina' WHERE id = 1;",
                    result -> result.affectedRows() == 1),
                success(
                    "Updated row: Dina",
                    "SELECT * FROM demo WHERE id = 1;",
                    result -> result.rows().equals(List.of(demoRow(1, "Dina")))),
                success("Abort update", "ABORT;", result -> result.message().equals("ABORT")),
                success(
                    "Aborted update: Ayu restored",
                    "SELECT * FROM demo WHERE id = 1;",
                    result -> result.rows().equals(List.of(demoRow(1, "Ayu")))),
                success(
                    "Delete: only id 4 affected",
                    "DELETE FROM demo WHERE id = 4;",
                    result -> result.affectedRows() == 1),
                success(
                    "Deleted row: 0 rows",
                    "SELECT * FROM demo WHERE id = 4;",
                    result -> result.rows().isEmpty()),
                success(
                    "Create index status",
                    "CREATE INDEX idx_demo_id ON demo(id);",
                    result -> result.message().equals("Index idx_demo_id created")),
                success("Begin commit", "BEGIN;", result -> result.message().equals("BEGIN")),
                success("Commit", "COMMIT;", result -> result.message().equals("COMMIT")),
                new Scenario(
                    "Invalid SQL", "nonsense;", Optional.of(ErrorCode.SQL_SYNTAX), result -> false),
                new Scenario(
                    "Execution failure",
                    "SELECT * FROM fail;",
                    Optional.of(ErrorCode.STORAGE_FAILURE),
                    result -> false),
                success(
                    "Continue after error: ids 1, 2, 3",
                    "SELECT * FROM demo;",
                    result -> ids(result).equals(List.of(1, 2, 3)))));
  }

  private static List<Integer> ids(QueryResult result) {
    return result.rows().stream().map(row -> ((IntValue) row.values().getFirst()).value()).toList();
  }

  private static Row demoRow(int id, String name) {
    return new Row(List.of(new IntValue(id), new TextValue(name)));
  }

  private static Scenario success(
      String name, String sql, java.util.function.Predicate<QueryResult> check) {
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
        "Standalone QP driver. :suite checks results; :file checks execution; :quit exits.");
    SessionId session = processor.openSession("interactive-driver");
    try {
      SqlStatementReader statements = new SqlStatementReader(input);
      ResultRenderer renderer = new ResultRenderer();
      while (true) {
        output.print("driver> ");
        output.flush();
        String sql = statements.next();
        if (sql == null) break;
        String command = sql.replaceFirst(";\\s*$", "").strip();
        if (command.equalsIgnoreCase(":quit")
            || command.equalsIgnoreCase("exit")
            || command.equalsIgnoreCase("quit")) break;
        try {
          if (command.equals(":suite")) printReport(runDemoSuite(), output);
          else if (command.startsWith(":file "))
            printReport(runFile(Path.of(command.substring(6).strip())), output);
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
