package dynamitedb.qo.join;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.Value;
import dynamitedb.qo.pipeline.PlanRewrite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Memilih pembacaan lewat index atau scan sekuensial (QO-13). */
public class AccessPathOptimizer implements PlanRewrite {

  private static final double RANGE_SELECTIVITY = 1.0 / 3;

  private final CatalogAccess catalog;

  public AccessPathOptimizer(CatalogAccess catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public PlanNode rewrite(PlanNode plan) {
    return switch (plan) {
      case FilterPlan filter -> rewriteFilter(filter);
      case ProjectPlan project -> new ProjectPlan(rewrite(project.child()), project.columns());
      case SortPlan sort -> new SortPlan(rewrite(sort.child()), sort.keys());
      case JoinPlan join ->
          new JoinPlan(rewrite(join.left()), rewrite(join.right()), join.kind(), join.condition());
      case UpdatePlan update ->
          new UpdatePlan(rewrite(update.source()), update.table(), update.target(), update.value());
      case DeletePlan delete -> new DeletePlan(rewrite(delete.source()), delete.table());
      default -> plan;
    };
  }

  private PlanNode rewriteFilter(FilterPlan filter) {
    PlanNode child = rewrite(filter.child());
    if (child instanceof TableScanPlan scan) {
      return accessPath(scan, filter.predicate())
          .orElseGet(() -> new FilterPlan(scan, filter.predicate()));
    }
    if (child instanceof IndexScanPlan scan) {
      return new IndexScanPlan(
          scan.index(),
          scan.table(),
          scan.alias(),
          scan.predicate(),
          PlanExpressions.combine(scan.residual(), filter.predicate()));
    }
    return new FilterPlan(child, filter.predicate());
  }

  private Optional<PlanNode> accessPath(TableScanPlan scan, Expression predicate) {
    List<Expression> conjuncts = PlanExpressions.conjuncts(predicate);
    List<IndexMetadata> available = catalog.listIndexes(scan.table());
    List<IndexMetadata> indexes =
        (available == null ? List.<IndexMetadata>of() : available)
            .stream()
                .sorted(Comparator.comparing(IndexMetadata::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    if (indexes.isEmpty()) return Optional.empty();

    TableStatistics statistics = catalog.getStatistics(scan.table());
    Match best = null;
    for (int i = 0; i < conjuncts.size(); i++) {
      Comparison comparison = comparison(conjuncts.get(i));
      if (comparison == null) continue;
      for (IndexMetadata index : indexes) {
        if (!sameColumn(index, comparison.column())) continue;
        if (!inScope(scan, comparison.column())) continue;
        if (!supports(index.kind(), comparison.operator())) continue;
        double selectivity = selectivity(statistics, comparison);
        if (best == null || selectivity < best.selectivity()) {
          best = new Match(i, index, comparison, selectivity);
        }
      }
    }
    if (best == null) return Optional.empty();

    double estimatedRows = statistics.rowCount() * best.selectivity();
    double indexCost = 1 + estimatedRows;
    if (indexCost >= statistics.blockCount()) return Optional.empty();

    List<Expression> remaining = new ArrayList<>(conjuncts);
    remaining.remove(best.position());
    Optional<Expression> residual =
        remaining.isEmpty() ? Optional.empty() : Optional.of(PlanExpressions.and(remaining));
    IndexPredicate indexPredicate =
        new IndexPredicate(
            ComparisonOp.valueOf(best.comparison().operator().name()), best.comparison().value());
    return Optional.of(
        new IndexScanPlan(
            best.index().name(), scan.table(), scan.alias(), indexPredicate, residual));
  }

  private static Comparison comparison(Expression expression) {
    if (!(expression instanceof BinaryExpression binary)) return null;
    if (!isComparison(binary.operator())) return null;
    if (binary.left() instanceof ColumnExpression column
        && binary.right() instanceof LiteralExpression literal) {
      return new Comparison(column.column(), binary.operator(), literal.value());
    }
    if (binary.left() instanceof LiteralExpression literal
        && binary.right() instanceof ColumnExpression column) {
      return new Comparison(column.column(), flip(binary.operator()), literal.value());
    }
    return null;
  }

  private static boolean sameColumn(IndexMetadata index, ColumnRef column) {
    return index.column().equalsIgnoreCase(column.column());
  }

  private static boolean inScope(TableScanPlan scan, ColumnRef column) {
    if (column.relation().isEmpty()) return true;
    String relation = column.relation().get();
    return relation.equalsIgnoreCase(scan.alias()) || relation.equalsIgnoreCase(scan.table());
  }

  private static boolean supports(IndexKind kind, BinaryOp operator) {
    return switch (kind) {
      case HASH -> operator == BinaryOp.EQ;
      case B_PLUS_TREE ->
          operator == BinaryOp.EQ
              || operator == BinaryOp.LT
              || operator == BinaryOp.LTE
              || operator == BinaryOp.GT
              || operator == BinaryOp.GTE;
    };
  }

  private static double selectivity(TableStatistics statistics, Comparison comparison) {
    if (comparison.operator() != BinaryOp.EQ) return RANGE_SELECTIVITY;
    long distinct = statistics.distinctCounts().getOrDefault(comparison.column().column(), 1L);
    return 1.0 / Math.max(1, distinct);
  }

  private static boolean isComparison(BinaryOp operator) {
    return switch (operator) {
      case EQ, NEQ, LT, LTE, GT, GTE -> true;
      default -> false;
    };
  }

  private static BinaryOp flip(BinaryOp operator) {
    return switch (operator) {
      case LT -> BinaryOp.GT;
      case LTE -> BinaryOp.GTE;
      case GT -> BinaryOp.LT;
      case GTE -> BinaryOp.LTE;
      default -> operator;
    };
  }

  private record Comparison(ColumnRef column, BinaryOp operator, Value value) {}

  private record Match(
      int position, IndexMetadata index, Comparison comparison, double selectivity) {}
}
