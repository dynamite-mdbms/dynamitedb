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
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccessPathOptimizerTest {

  private final CatalogAccess catalog =
      catalog(
          Map.of(
              "student", new TableStatistics(1_000, 400, 50, Map.of("id", 100L, "age", 20L)),
              "flat", new TableStatistics(100, 10, 50, Map.of("id", 1L)),
              "range_heavy", new TableStatistics(1_000, 100, 50, Map.of("age", 20L))),
          List.of(
              new IndexMetadata("idx_student_id", "student", "id", IndexKind.HASH),
              new IndexMetadata("idx_student_age", "student", "age", IndexKind.B_PLUS_TREE),
              new IndexMetadata("idx_flat_id", "flat", "id", IndexKind.HASH),
              new IndexMetadata(
                  "idx_range_heavy_age", "range_heavy", "age", IndexKind.B_PLUS_TREE)));

  @Test
  void choosesHashIndexForEqualityPredicate() {
    Expression predicate =
        compare(columnExpression("s", "id"), BinaryOp.EQ, literal(new IntValue(42)));
    PlanNode plan = new FilterPlan(scan("student", "s"), predicate);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    IndexScanPlan indexScan = assertInstanceOf(IndexScanPlan.class, rewritten);
    assertEquals("idx_student_id", indexScan.index());
    assertEquals("student", indexScan.table());
    assertEquals("s", indexScan.alias());
    assertEquals(new IndexPredicate(ComparisonOp.EQ, new IntValue(42)), indexScan.predicate());
    assertEquals(Optional.empty(), indexScan.residual());
  }

  // pencarian NULL tidak didukung index, jadi IS [NOT] NULL selalu lewat scan + filter
  @Test
  void neverUsesIndexForIsNullPredicates() {
    for (String column : List.of("id", "age")) {
      for (BinaryOp op : List.of(BinaryOp.IS, BinaryOp.IS_NOT)) {
        PlanNode plan =
            new FilterPlan(
                scan("student", "s"),
                compare(columnExpression("s", column), op, literal(NullValue.INSTANCE)));
        assertEquals(plan, new AccessPathOptimizer(catalog).rewrite(plan));
      }
    }
  }

  @Test
  void keepsIsNullAsResidualWhenAnotherConjunctUsesAnIndex() {
    Expression isNull =
        compare(columnExpression("s", "age"), BinaryOp.IS, literal(NullValue.INSTANCE));
    Expression equality =
        compare(columnExpression("s", "id"), BinaryOp.EQ, literal(new IntValue(42)));
    PlanNode plan = new FilterPlan(scan("student", "s"), compare(isNull, BinaryOp.AND, equality));

    IndexScanPlan indexScan =
        assertInstanceOf(IndexScanPlan.class, new AccessPathOptimizer(catalog).rewrite(plan));
    assertEquals("idx_student_id", indexScan.index());
    assertEquals(Optional.of(isNull), indexScan.residual());
  }

  @Test
  void keepsRemainingConjunctsAsResidualFilter() {
    Expression equality =
        compare(columnExpression("s", "id"), BinaryOp.EQ, literal(new IntValue(42)));
    Expression range =
        compare(columnExpression("s", "age"), BinaryOp.GT, literal(new IntValue(20)));
    Expression predicate = compare(equality, BinaryOp.AND, range);
    PlanNode plan = new FilterPlan(scan("student", "s"), predicate);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    IndexScanPlan indexScan = assertInstanceOf(IndexScanPlan.class, rewritten);
    assertEquals("idx_student_id", indexScan.index());
    assertEquals(Optional.of(range), indexScan.residual());
  }

  @Test
  void choosesBPlusTreeIndexForRangePredicate() {
    Expression predicate =
        compare(columnExpression("s", "age"), BinaryOp.GTE, literal(new IntValue(20)));
    PlanNode plan = new FilterPlan(scan("student", "s"), predicate);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    IndexScanPlan indexScan = assertInstanceOf(IndexScanPlan.class, rewritten);
    assertEquals("idx_student_age", indexScan.index());
    assertEquals(new IndexPredicate(ComparisonOp.GTE, new IntValue(20)), indexScan.predicate());
  }

  @Test
  void rejectsRangePredicateForHashIndex() {
    Expression predicate =
        compare(columnExpression("s", "id"), BinaryOp.GT, literal(new IntValue(10)));
    PlanNode plan = new FilterPlan(scan("student", "s"), predicate);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    FilterPlan filter = assertInstanceOf(FilterPlan.class, rewritten);
    assertEquals(predicate, filter.predicate());
    assertInstanceOf(TableScanPlan.class, filter.child());
  }

  @Test
  void keepsSequentialScanWhenIndexIsNotSelectiveEnough() {
    Expression predicate =
        compare(columnExpression("f", "id"), BinaryOp.EQ, literal(new IntValue(1)));
    PlanNode plan = new FilterPlan(scan("flat", "f"), predicate);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    assertInstanceOf(FilterPlan.class, rewritten);
  }

  @Test
  void keepsSequentialScanWhenSelectiveIndexStillCostsMore() {
    Expression predicate =
        compare(columnExpression("r", "age"), BinaryOp.GT, literal(new IntValue(20)));
    PlanNode plan = new FilterPlan(scan("range_heavy", "r"), predicate);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    FilterPlan filter = assertInstanceOf(FilterPlan.class, rewritten);
    assertInstanceOf(TableScanPlan.class, filter.child());
  }

  @Test
  void flipsLiteralOnTheLeftOfComparison() {
    Expression predicate =
        compare(literal(new IntValue(42)), BinaryOp.EQ, columnExpression("s", "id"));
    PlanNode plan = new FilterPlan(scan("student", "s"), predicate);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    IndexScanPlan indexScan = assertInstanceOf(IndexScanPlan.class, rewritten);
    assertEquals(new IndexPredicate(ComparisonOp.EQ, new IntValue(42)), indexScan.predicate());
  }

  @Test
  void mergesFilterIntoExistingIndexScanResidual() {
    IndexPredicate equality = new IndexPredicate(ComparisonOp.EQ, new IntValue(42));
    IndexScanPlan indexScan =
        new IndexScanPlan("idx_student_id", "student", "s", equality, Optional.empty());
    Expression residual =
        compare(columnExpression("s", "age"), BinaryOp.GT, literal(new IntValue(20)));
    PlanNode plan = new FilterPlan(indexScan, residual);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    IndexScanPlan merged = assertInstanceOf(IndexScanPlan.class, rewritten);
    assertEquals(equality, merged.predicate());
    assertEquals(Optional.of(residual), merged.residual());
  }

  @Test
  void findsFilterBelowProjection() {
    Expression predicate =
        compare(columnExpression("s", "id"), BinaryOp.EQ, literal(new IntValue(42)));
    List<ColumnRef> columns = List.of(Qo5TestSupport.column("s", "id"));
    PlanNode plan = new ProjectPlan(new FilterPlan(scan("student", "s"), predicate), columns);

    PlanNode rewritten = new AccessPathOptimizer(catalog).rewrite(plan);

    ProjectPlan project = assertInstanceOf(ProjectPlan.class, rewritten);
    assertTrue(project.child() instanceof IndexScanPlan);
  }
}
