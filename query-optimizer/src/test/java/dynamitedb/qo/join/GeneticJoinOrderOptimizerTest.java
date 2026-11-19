package dynamitedb.qo.join;

import static dynamitedb.qo.join.Qo5TestSupport.catalog;
import static dynamitedb.qo.join.Qo5TestSupport.columnExpression;
import static dynamitedb.qo.join.Qo5TestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.qo.binding.CostEstimator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GeneticJoinOrderOptimizerTest {

  private static final Map<String, List<String>> COLUMNS =
      Map.of(
          "student", List.of("sid"),
          "enroll", List.of("sid", "cid"),
          "course", List.of("cid"));

  private static final Map<String, TableStatistics> STATISTICS =
      Map.of(
          "student", stats(1_000, 100, Map.of("sid", 1_000L)),
          "enroll", stats(50_000, 500, Map.of("sid", 1_000L, "cid", 100L)),
          "course", stats(10, 2, Map.of("cid", 10L)));

  @Test
  void seededSearchIsRepeatableAndNeverReturnsAPlanCostlierThanBaseline() {
    PlanNode original = original();
    var first = optimizer(86L).optimize(original);
    var second = optimizer(86L).optimize(original);

    assertEquals(first.candidates(), second.candidates());
    assertEquals(first.bestPlan(), second.bestPlan());
    assertEquals(first.bestCost(), second.bestCost());
    assertTrue(first.bestCost() <= first.baselineCost());
    assertTrue(first.candidates().stream().allMatch(GeneticJoinOrderOptimizerTest::validJoinTree));
    assertTrue(first.candidates().stream().allMatch(plan -> leafCount(plan) == 3));
  }

  @Test
  void limitsThePopulationToTheAvailablePermutationsForSmallJoins() {
    var config = new GeneticJoinOrderOptimizer.Config(32, 5, 0.2, 3, 2, 8);
    PlanNode pair =
        new JoinPlan(scan("student"), scan("course"), JoinKind.CARTESIAN, Optional.empty());

    var result =
        new GeneticJoinOrderOptimizer(new CostEstimator(catalog(STATISTICS, COLUMNS)), config, 1)
            .optimize(pair);

    assertEquals(2, result.candidates().size());
  }

  @Test
  void rejectsInvalidSearchConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GeneticJoinOrderOptimizer.Config(1, 10, 0.1, 1, 0, 4));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GeneticJoinOrderOptimizer.Config(8, 10, 1.1, 2, 1, 4));
  }

  private static GeneticJoinOrderOptimizer optimizer(long seed) {
    return new GeneticJoinOrderOptimizer(
        new CostEstimator(catalog(STATISTICS, COLUMNS)),
        new GeneticJoinOrderOptimizer.Config(12, 8, 0.25, 3, 1, 8),
        seed);
  }

  private static PlanNode original() {
    var condition =
        Qo5TestSupport.compare(
            columnExpression("s", "sid"), BinaryOp.EQ, columnExpression("e", "sid"));
    return new JoinPlan(
        new JoinPlan(
            scan("student", "s"), scan("enroll", "e"), JoinKind.THETA, Optional.of(condition)),
        scan("course", "c"),
        JoinKind.CARTESIAN,
        Optional.empty());
  }

  private static boolean validJoinTree(PlanNode plan) {
    if (!(plan instanceof JoinPlan join)) return true;
    return validJoinTree(join.left()) && validJoinTree(join.right());
  }

  private static int leafCount(PlanNode plan) {
    if (!(plan instanceof JoinPlan join)) return 1;
    return leafCount(join.left()) + leafCount(join.right());
  }

  private static TableStatistics stats(long rows, long blocks, Map<String, Long> distinctCounts) {
    return new TableStatistics(rows, blocks, 32, distinctCounts);
  }
}
