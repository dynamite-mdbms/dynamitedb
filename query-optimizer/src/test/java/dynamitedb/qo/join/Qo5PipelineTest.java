package dynamitedb.qo.join;

import static dynamitedb.qo.join.Qo5TestSupport.catalog;
import static dynamitedb.qo.join.Qo5TestSupport.columnExpression;
import static dynamitedb.qo.join.Qo5TestSupport.compare;
import static dynamitedb.qo.join.Qo5TestSupport.literal;
import static dynamitedb.qo.join.Qo5TestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Qo5PipelineTest {

  @Test
  void rewritesJoinThenFindsIndexesOnBothSides() {
    CatalogAccess catalog =
        catalog(
            Map.of(
                "student", new TableStatistics(1_000, 100, 50, Map.of("id", 100L)),
                "course", new TableStatistics(500, 50, 50, Map.of("id", 100L))),
            List.of(
                new IndexMetadata("idx_student_id", "student", "id", IndexKind.HASH),
                new IndexMetadata("idx_course_id", "course", "id", IndexKind.HASH)));
    PlanNode left =
        new FilterPlan(
            scan("student", "s"),
            compare(columnExpression("s", "id"), BinaryOp.EQ, literal(new IntValue(1))));
    PlanNode right =
        new FilterPlan(
            scan("course", "c"),
            compare(columnExpression("c", "id"), BinaryOp.EQ, literal(new IntValue(2))));
    Expression joinPredicate =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("c", "student_id"));
    PlanNode plan =
        new FilterPlan(
            new JoinPlan(left, right, JoinKind.CARTESIAN, Optional.empty()), joinPredicate);

    CostEstimator cost =
        new CostEstimator(catalog) {
          @Override
          public double estimate(PlanNode candidate) {
            return 1.0;
          }
        };
    OptimizerPipeline pipeline =
        new OptimizerPipeline(
            List.of(new JoinRewriter(), new AccessPathOptimizer(catalog)),
            new JoinOrderOptimizer(catalog),
            new BestPlanSelector(cost));

    PlanNode optimized = pipeline.optimize(plan);

    JoinPlan join = assertInstanceOf(JoinPlan.class, optimized);
    assertEquals(JoinKind.THETA, join.kind());
    assertInstanceOf(IndexScanPlan.class, join.left());
    assertInstanceOf(IndexScanPlan.class, join.right());
  }

  @Test
  void pipelineKeepsTheCheaperOfTheGeneticAndHeuristicPlans() {
    CatalogAccess catalog =
        catalog(
            Map.of(
                "student", new TableStatistics(1_000, 100, 50, Map.of("id", 100L)),
                "course", new TableStatistics(500, 50, 50, Map.of("id", 100L)),
                "enroll", new TableStatistics(5_000, 500, 50, Map.of("id", 500L))),
            List.of());
    CostEstimator costs = new CostEstimator(catalog);
    PlanNode plan =
        new JoinPlan(
            new JoinPlan(
                scan("student", "s"), scan("course", "c"), JoinKind.CARTESIAN, Optional.empty()),
            scan("enroll", "e"),
            JoinKind.CARTESIAN,
            Optional.empty());
    double genetic = genetic(costs, 3, 41L).optimize(plan).bestCost();
    double heuristic =
        costs.estimate(
            new BestPlanSelector(costs).choose(new JoinOrderOptimizer(catalog).enumerate(plan)));
    OptimizerPipeline pipeline =
        new OptimizerPipeline(
            List.of(),
            new JoinOrderOptimizer(catalog),
            new BestPlanSelector(costs),
            genetic(costs, 3, 41L));

    assertEquals(Math.min(genetic, heuristic), costs.estimate(pipeline.optimize(plan)));
  }

  @Test
  void pipelinePicksTheGeneticPlanWhenTheHeuristicCannotSearch() {
    // Seven leaves exceed the heuristic's six-leaf limit, so only the genetic search reorders.
    Map<String, TableStatistics> statistics = new LinkedHashMap<>();
    PlanNode plan = null;
    for (int table = 7; table >= 1; table--) {
      String name = "t" + table;
      statistics.put(name, new TableStatistics(table * 1_000L, table * 100L, 50, Map.of()));
      PlanNode leaf = scan(name);
      plan = plan == null ? leaf : new JoinPlan(plan, leaf, JoinKind.CARTESIAN, Optional.empty());
    }
    CatalogAccess catalog = catalog(statistics, List.of());
    CostEstimator costs = new CostEstimator(catalog);
    PlanNode expected = genetic(costs, 8, 5L).optimize(plan).bestPlan();
    OptimizerPipeline pipeline =
        new OptimizerPipeline(
            List.of(),
            new JoinOrderOptimizer(catalog),
            new BestPlanSelector(costs),
            genetic(costs, 8, 5L));

    PlanNode optimized = pipeline.optimize(plan);

    assertEquals(expected, optimized);
    assertTrue(costs.estimate(optimized) < costs.estimate(plan));
  }

  private static GeneticJoinOrderOptimizer genetic(CostEstimator costs, int tables, long seed) {
    return new GeneticJoinOrderOptimizer(
        costs, new GeneticJoinOrderOptimizer.Config(12, 10, 0.2, 3, 2, tables), seed);
  }
}
