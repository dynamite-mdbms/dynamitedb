package dynamitedb.qo.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.JoinOrderOptimizer;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.plan.PlanVisualizer;
import dynamitedb.qo.verification.QueryOptimizerDriver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryOptimizerImplTest {

  @Test
  void preparesAndExplainsThroughCanonicalFacade() {
    QueryOptimizerImpl optimizer = optimizer();

    var prepared = optimizer.prepare("SELECT name FROM student ORDER BY name");

    assertInstanceOf(ProjectPlan.class, prepared.root());
    assertEquals(
        new Schema(List.of(new Column("student", "name", DataType.VARCHAR, 50, false))),
        prepared.outputSchema());
    assertEquals(7.0, optimizer.estimateCost(prepared.root()));
    var explanation = optimizer.explain("SELECT name FROM student");
    assertTrue(explanation.before().contains("Project"));
    assertTrue(explanation.after().contains("TableScan"));
    assertEquals(7.0, explanation.cost());
  }

  @Test
  void returnsEmptyOutputSchemaForNonSelectStatements() {
    assertEquals(new Schema(List.of()), optimizer().prepare("BEGIN").outputSchema());
  }

  @Test
  void driverReportsBothPlansAndStructuredSyntaxFailures() {
    var report =
        new QueryOptimizerDriver(optimizer())
            .runSuite(List.of("SELECT * FROM student", "SELEC * FROM student"));

    assertTrue(report.passed());
    assertEquals(2, report.results().size());
    assertTrue(report.results().get(0).message().contains("Project"));
    assertTrue(report.results().get(1).message().contains("SQL_SYNTAX"));
  }

  private static QueryOptimizerImpl optimizer() {
    CatalogAccess catalog = new FakeCatalog();
    CostEstimator cost =
        new CostEstimator(catalog) {
          @Override
          public double estimate(dynamitedb.contracts.query.PlanNode plan) {
            return 7.0;
          }
        };
    return new QueryOptimizerImpl(
        new Lexer(),
        new SqlParser(new StatementParser(), new ExpressionParser()),
        new Binder(catalog),
        new PlanBuilder(),
        new OptimizerPipeline(List.of(), new JoinOrderOptimizer(), new BestPlanSelector(cost)),
        cost,
        new PlanVisualizer());
  }

  private static final class FakeCatalog implements CatalogAccess {
    private final Schema schema =
        new Schema(List.of(new Column("student", "name", DataType.VARCHAR, 50, false)));

    @Override
    public List<String> listTables() {
      return List.of("student");
    }

    @Override
    public long getTableId(String table) {
      return 1;
    }

    @Override
    public Schema getSchema(String table) {
      return schema;
    }

    @Override
    public TableStatistics getStatistics(String table) {
      return new TableStatistics(1, 1, 50, Map.of("name", 1L));
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return List.of();
    }
  }
}
