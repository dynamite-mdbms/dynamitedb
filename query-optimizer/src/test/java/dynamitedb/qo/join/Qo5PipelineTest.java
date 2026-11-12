package dynamitedb.qo.join;

import static dynamitedb.qo.join.Qo5TestSupport.catalog;
import static dynamitedb.qo.join.Qo5TestSupport.columnExpression;
import static dynamitedb.qo.join.Qo5TestSupport.compare;
import static dynamitedb.qo.join.Qo5TestSupport.literal;
import static dynamitedb.qo.join.Qo5TestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
}
