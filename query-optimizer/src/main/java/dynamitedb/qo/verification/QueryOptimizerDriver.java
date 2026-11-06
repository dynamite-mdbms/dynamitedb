package dynamitedb.qo.verification;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Menguji QO lewat port QueryOptimizer tanpa Storage Manager asli (QO-16). */
public class QueryOptimizerDriver {

  private static final String EXPECT_PREFIX = "-- expect:";

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

  private TestResult runScenario(String scenario) {
    String expectation = expectation(scenario);
    String query = sql(scenario);
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
      return new TestResult(
          true,
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
              + explanation.cost());
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

  private static List<String> builtInScenarios() {
    return List.of(
        "-- expect: OK\nSELECT student.name FROM student ORDER BY student.name DESC;",
        "-- expect: OK\nSELECT * FROM student NATURAL JOIN enrollment;",
        "-- expect: OK\n"
            + "SELECT student.name FROM student JOIN enrollment "
            + "ON student.student_id = enrollment.student_id WHERE gpa > 3.0;",
        "-- expect: SQL_SYNTAX\nSELEC * FROM student;",
        "-- expect: SQL_BIND\nSELECT * FROM missing_table;");
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
              && !leading.toLowerCase(Locale.ROOT).startsWith(EXPECT_PREFIX))) continue;
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

  private static String expectation(String scenario) {
    String firstLine = scenario.lines().findFirst().orElse("").strip();
    if (!firstLine.toLowerCase(Locale.ROOT).startsWith(EXPECT_PREFIX)) return null;
    return firstLine.substring(EXPECT_PREFIX.length()).strip().toUpperCase(Locale.ROOT);
  }

  private static String sql(String scenario) {
    String firstLine = scenario.lines().findFirst().orElse("").strip();
    if (!firstLine.toLowerCase(Locale.ROOT).startsWith(EXPECT_PREFIX)) return scenario.strip();
    int newline = scenario.indexOf('\n');
    return newline < 0 ? "" : scenario.substring(newline + 1).strip();
  }

  private static QueryOptimizer standaloneOptimizer() {
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

  private static CatalogAccess fakeCatalog() {
    Map<String, Schema> schemas =
        Map.of(
            "student",
            new Schema(
                List.of(
                    new Column("student", "student_id", DataType.INTEGER, 0, false),
                    new Column("student", "name", DataType.VARCHAR, 50, false),
                    new Column("student", "gpa", DataType.FLOAT, 0, true))),
            "enrollment",
            new Schema(
                List.of(
                    new Column("enrollment", "student_id", DataType.INTEGER, 0, false),
                    new Column("enrollment", "course_id", DataType.INTEGER, 0, false))));
    return new CatalogAccess() {
      @Override
      public List<String> listTables() {
        return schemas.keySet().stream().sorted().toList();
      }

      @Override
      public long getTableId(String table) {
        requireTable(table, schemas);
        return table.equals("student") ? 1 : 2;
      }

      @Override
      public Schema getSchema(String table) {
        requireTable(table, schemas);
        return schemas.get(table);
      }

      @Override
      public TableStatistics getStatistics(String table) {
        requireTable(table, schemas);
        Schema schema = schemas.get(table);
        Map<String, Long> distinct =
            schema.columns().stream()
                .collect(java.util.stream.Collectors.toMap(Column::name, c -> 3L));
        return new TableStatistics(3, 1, 64, distinct);
      }

      @Override
      public List<IndexMetadata> listIndexes(String table) {
        requireTable(table, schemas);
        return List.of();
      }
    };
  }

  private static void requireTable(String table, Map<String, Schema> schemas) {
    if (!schemas.containsKey(table)) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Unknown table: " + table);
    }
  }
}
