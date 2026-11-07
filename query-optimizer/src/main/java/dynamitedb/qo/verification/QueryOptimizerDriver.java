package dynamitedb.qo.verification;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.facade.QueryOptimizerImpl;
import dynamitedb.qo.join.AccessPathOptimizer;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.JoinOrderOptimizer;
import dynamitedb.qo.join.JoinRewriter;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import dynamitedb.qo.pipeline.PlanRewrite;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.plan.PlanVisualizer;
import dynamitedb.qo.pushdown.ProjectionPushdown;
import dynamitedb.qo.pushdown.SelectionPushdown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Menguji QO lewat port QueryOptimizer tanpa Storage Manager asli (QO-16). */
public class QueryOptimizerDriver {

  private static final String EXPECT_PREFIX = "-- expect:";
  private static final String AFTER_PREFIX = "-- expect-after:";
  private static final String SCENARIO_RESOURCE = "/qo1-driver.sql";

  private final QueryOptimizer optimizer;

  public QueryOptimizerDriver(QueryOptimizer optimizer) {
    this.optimizer = optimizer;
  }

  public TestReport runSuite(List<String> queries) {
    List<TestResult> results = new ArrayList<>();
    for (String scenario : queries) results.add(runScenario(scenario));
    return new TestReport(results);
  }

  public static void main(String[] args) throws IOException {
    List<String> scenarios = args.length == 0 ? builtInScenarios() : readScenarios(List.of(args));
    TestReport report = new QueryOptimizerDriver(standaloneOptimizer()).runSuite(scenarios);

    for (int index = 0; index < report.results().size(); index++) {
      TestResult result = report.results().get(index);
      System.out.println("[" + (result.passed() ? "PASS" : "FAIL") + "] Scenario " + (index + 1));
      System.out.println(result.message());
      System.out.println();
    }
    System.out.println(
        "SUMMARY: "
            + report.results().stream().filter(TestResult::passed).count()
            + "/"
            + report.results().size()
            + " scenarios passed");
    if (!report.passed()) throw new IllegalStateException("One or more QO driver scenarios failed");
  }

  private TestResult runScenario(String text) {
    Scenario scenario = Scenario.parse(text);
    String expectation = scenario.expectation();
    String query = scenario.sql();
    if (query.isBlank()) return new TestResult(false, "FAIL: scenario contains no SQL");
    try {
      var explanation = optimizer.explain(query);
      boolean complete =
          explanation != null
              && explanation.before() != null
              && explanation.after() != null
              && !Double.isNaN(explanation.cost());
      if (!complete) return new TestResult(false, "FAIL: incomplete explanation for " + query);
      if (expectation != null && !expectation.equals("OK")) {
        return new TestResult(
            false, "FAIL: expected " + expectation + " but query succeeded: " + query);
      }
      String plans =
          "SQL: "
              + query
              + System.lineSeparator()
              + "BEFORE:"
              + System.lineSeparator()
              + explanation.before()
              + System.lineSeparator()
              + "AFTER:"
              + System.lineSeparator()
              + explanation.after()
              + System.lineSeparator()
              + "COST: "
              + explanation.cost();
      List<String> missing =
          scenario.afterContains().stream()
              .filter(expected -> !explanation.after().contains(expected))
              .toList();
      if (!missing.isEmpty()) {
        return new TestResult(
            false, "FAIL: AFTER plan lacks " + missing + System.lineSeparator() + plans);
      }
      return new TestResult(true, plans);
    } catch (DbmsException handled) {
      boolean expected = expectation != null && expectation.equals(handled.getCode().name());
      return new TestResult(
          expected,
          (expected ? "HANDLED " : "FAIL: unexpected ")
              + handled.getCode()
              + ": "
              + query
              + " -> "
              + handled.getMessage());
    } catch (RuntimeException unexpected) {
      return new TestResult(
          false,
          "FAIL: "
              + query
              + " -> "
              + unexpected.getClass().getSimpleName()
              + ": "
              + unexpected.getMessage());
    }
  }

  /** Scenarios bundled from {@code examples/qo1-driver.sql}, the single source of the demo. */
  static List<String> builtInScenarios() throws IOException {
    try (var in = QueryOptimizerDriver.class.getResourceAsStream(SCENARIO_RESOURCE)) {
      if (in == null) throw new IOException("Missing driver resource " + SCENARIO_RESOURCE);
      return splitSqlScript(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static List<String> readScenarios(List<String> files) throws IOException {
    List<String> scenarios = new ArrayList<>();
    for (String file : files) scenarios.addAll(splitSqlScript(Files.readString(Path.of(file))));
    return List.copyOf(scenarios);
  }

  static List<String> splitSqlScript(String script) {
    StringBuilder filtered = new StringBuilder();
    for (String line : script.lines().toList()) {
      String leading = line.stripLeading();
      if (leading.startsWith("#")
          || (leading.startsWith("--")
              && !leading.toLowerCase(Locale.ROOT).startsWith("-- expect"))) continue;
      filtered.append(line).append('\n');
    }

    List<String> scenarios = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    for (int index = 0; index < filtered.length(); index++) {
      char character = filtered.charAt(index);
      current.append(character);
      if (quote == 0 && (character == '\'' || character == '"')) {
        quote = character;
      } else if (quote != 0 && character == quote) {
        if (index + 1 < filtered.length() && filtered.charAt(index + 1) == quote) {
          current.append(filtered.charAt(++index));
        } else {
          quote = 0;
        }
      } else if (quote == 0 && character == ';') {
        if (!current.toString().isBlank()) scenarios.add(current.toString().strip());
        current.setLength(0);
      }
    }
    if (!current.toString().isBlank()) scenarios.add(current.toString().strip());
    return List.copyOf(scenarios);
  }

  /** Leading {@code -- expect:} and {@code -- expect-after:} headers followed by one SQL. */
  private record Scenario(String expectation, List<String> afterContains, String sql) {

    static Scenario parse(String text) {
      String expectation = null;
      List<String> after = new ArrayList<>();
      StringBuilder sql = new StringBuilder();
      for (String line : text.lines().toList()) {
        String header = line.strip();
        String lower = header.toLowerCase(Locale.ROOT);
        if (sql.toString().isBlank() && lower.startsWith(AFTER_PREFIX)) {
          after.add(header.substring(AFTER_PREFIX.length()).strip());
        } else if (sql.toString().isBlank() && lower.startsWith(EXPECT_PREFIX)) {
          expectation = header.substring(EXPECT_PREFIX.length()).strip().toUpperCase(Locale.ROOT);
        } else {
          sql.append(line).append('\n');
        }
      }
      return new Scenario(expectation, List.copyOf(after), sql.toString().strip());
    }
  }

  /** Full QO pipeline wired to the deterministic fake catalog below. */
  public static QueryOptimizer standaloneOptimizer() {
    CatalogAccess catalog = fakeCatalog();
    CostEstimator cost = new CostEstimator(catalog);
    Binder binder = new Binder(catalog);
    return new QueryOptimizerImpl(
        new Lexer(),
        new SqlParser(new StatementParser(), new ExpressionParser()),
        binder,
        new PlanBuilder(),
        new OptimizerPipeline(
            List.<PlanRewrite>of(
                new SelectionPushdown(),
                new ProjectionPushdown(catalog),
                new JoinRewriter(),
                new AccessPathOptimizer(catalog)),
            new JoinOrderOptimizer(),
            new BestPlanSelector(cost)),
        cost,
        new PlanVisualizer());
  }

  /**
   * Fake SM catalog: three tables with asymmetric statistics, a HASH and a B+Tree index, so the
   * driver can show join rewrite/reorder, an IndexScan being chosen, and a TableScan fallback.
   */
  static CatalogAccess fakeCatalog() {
    Map<String, FakeTable> tables =
        Map.of(
            "student",
            new FakeTable(
                1,
                new Schema(
                    List.of(
                        new Column("student", "student_id", DataType.INTEGER, 0, false),
                        new Column("student", "name", DataType.VARCHAR, 50, false),
                        new Column("student", "gpa", DataType.FLOAT, 0, true))),
                new TableStatistics(
                    1000, 100, 64, Map.of("student_id", 1000L, "name", 950L, "gpa", 40L)),
                List.of(
                    new IndexMetadata("idx_student_gpa", "student", "gpa", IndexKind.B_PLUS_TREE),
                    new IndexMetadata("idx_student_id", "student", "student_id", IndexKind.HASH))),
            "enrollment",
            new FakeTable(
                2,
                new Schema(
                    List.of(
                        new Column("enrollment", "student_id", DataType.INTEGER, 0, false),
                        new Column("enrollment", "course_id", DataType.INTEGER, 0, false),
                        new Column("enrollment", "grade", DataType.CHAR, 2, true))),
                new TableStatistics(
                    5000, 250, 32, Map.of("student_id", 1000L, "course_id", 50L, "grade", 5L)),
                List.of()),
            "course",
            new FakeTable(
                3,
                new Schema(
                    List.of(
                        new Column("course", "course_id", DataType.INTEGER, 0, false),
                        new Column("course", "title", DataType.VARCHAR, 50, false),
                        new Column("course", "credits", DataType.INTEGER, 0, false))),
                new TableStatistics(
                    50, 5, 64, Map.of("course_id", 50L, "title", 50L, "credits", 4L)),
                List.of()));
    return new CatalogAccess() {
      @Override
      public List<String> listTables() {
        return tables.keySet().stream().sorted().toList();
      }

      @Override
      public long getTableId(String table) {
        return table(table).id();
      }

      @Override
      public Schema getSchema(String table) {
        return table(table).schema();
      }

      @Override
      public TableStatistics getStatistics(String table) {
        return table(table).statistics();
      }

      @Override
      public List<IndexMetadata> listIndexes(String table) {
        return table(table).indexes();
      }

      private FakeTable table(String table) {
        FakeTable found = tables.get(table.toLowerCase(Locale.ROOT));
        if (found == null) throw new DbmsException(ErrorCode.SQL_BIND, "Unknown table: " + table);
        return found;
      }
    };
  }

  private record FakeTable(
      long id, Schema schema, TableStatistics statistics, List<IndexMetadata> indexes) {}
}
