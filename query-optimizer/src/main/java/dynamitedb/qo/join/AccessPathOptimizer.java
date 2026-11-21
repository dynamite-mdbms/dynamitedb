package dynamitedb.qo.join;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.pipeline.PlanRewrite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AccessPathOptimizer implements PlanRewrite {

  private final CatalogAccess catalog;
  private final CostEstimator cost;

  public AccessPathOptimizer(CatalogAccess catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.cost = new CostEstimator(catalog);
  }

  @Override
  public PlanNode rewrite(PlanNode plan) {
    return switch (plan) {
      case FilterPlan filter -> rewriteFilter(filter);
      case ProjectPlan project -> ProjectPlan.fromItems(rewrite(project.child()), project.items());
      case AggregatePlan aggregate ->
          new AggregatePlan(
              rewrite(aggregate.child()), aggregate.groupBy(), aggregate.aggregates());
      case SortPlan sort -> new SortPlan(rewrite(sort.child()), sort.keys());
      case LimitPlan limit -> new LimitPlan(rewrite(limit.child()), limit.count());
      case JoinPlan join ->
          new JoinPlan(rewrite(join.left()), rewrite(join.right()), join.kind(), join.condition());
      case UpdatePlan update ->
          new UpdatePlan(rewrite(update.source()), update.table(), update.assignments());
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

    // QO-13: an index scan must beat the sequential scan under the same QO-08 cost model.
    PlanNode best = null;
    double bestCost = cost.estimate(new FilterPlan(scan, predicate));
    for (int i = 0; i < conjuncts.size(); i++) {
      Comparison comparison = comparison(conjuncts.get(i));
      if (comparison == null) continue;
      for (IndexMetadata index : indexes) {
        if (!sameColumn(index, comparison.column())) continue;
        if (!inScope(scan, comparison.column())) continue;
        if (!supports(index.kind(), comparison.operator())) continue;
        Optional<Value> key =
            index.kind() == IndexKind.HASH
                ? hashKey(comparison.value(), columnType(scan.table(), index.column()))
                : Optional.of(comparison.value());
        if (key.isEmpty()) continue;
        List<Expression> remaining = new ArrayList<>(conjuncts);
        remaining.remove(i);
        IndexScanPlan candidate =
            new IndexScanPlan(
                index.name(),
                scan.table(),
                scan.alias(),
                new IndexPredicate(ComparisonOp.valueOf(comparison.operator().name()), key.get()),
                remaining.isEmpty()
                    ? Optional.empty()
                    : Optional.of(PlanExpressions.and(remaining)));
        double candidateCost = cost.estimate(candidate);
        if (candidateCost < bestCost) {
          best = candidate;
          bestCost = candidateCost;
        }
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * SM hash buckets match keys by exact Value type, so 7.0 must probe an INTEGER index as 7. A
   * fractional literal can never equal an INTEGER key; the sequential scan then handles it.
   */
  private static Optional<Value> hashKey(Value value, DataType column) {
    if (value instanceof IntValue number && column == DataType.FLOAT) {
      return Optional.of(new FloatValue(number.value()));
    }
    if (value instanceof FloatValue number && column == DataType.INTEGER) {
      int whole = (int) number.value();
      return whole == number.value() ? Optional.of(new IntValue(whole)) : Optional.empty();
    }
    return Optional.of(value);
  }

  private DataType columnType(String table, String column) {
    return catalog.getSchema(table).columns().stream()
        .filter(c -> c.name().equalsIgnoreCase(column))
        .map(Column::dataType)
        .findFirst()
        .orElse(null);
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
}
