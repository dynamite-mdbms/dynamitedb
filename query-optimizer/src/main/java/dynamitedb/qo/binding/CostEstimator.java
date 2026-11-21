package dynamitedb.qo.binding;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.CreateTablePlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.DropTablePlan;
import dynamitedb.contracts.query.ExistsSubqueryExpression;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.ScalarSubqueryExpression;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UnaryExpression;
import dynamitedb.contracts.query.UnaryOp;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CostEstimator {

  private static final double RANGE_SELECTIVITY = 1.0 / 3;

  private final CatalogAccess catalog;

  public CostEstimator(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  /**
   * Block transfers. SM's blockCount already folds record size in (b_r = n_r / f_r), so
   * averageRowBytes is not needed on its own; the BNLJ outer side is counted in rows because QP
   * buffers {@link JoinPlan#BNLJ_BLOCK_ROWS} rows per pass over the inner side.
   */
  public double estimate(PlanNode plan) {
    return switch (plan) {
      case TableScanPlan scan -> stats(scan.table()).blockCount();

      case IndexScanPlan scan ->
          1 + indexRows(scan) + scan.residual().map(this::subqueryCost).orElse(0.0);
      case FilterPlan filter -> estimate(filter.child()) + subqueryCost(filter.predicate());
      case ProjectPlan project ->
          estimate(project.child())
              + project.items().stream().mapToDouble(item -> subqueryCost(item.expression())).sum();
      // QP sorts and aggregates in memory, so neither adds block transfers.
      case AggregatePlan aggregate -> estimate(aggregate.child());

      case SortPlan sort -> estimate(sort.child());
      // Upper bound: a blocking sort below still reads every input row before the cap applies.
      case LimitPlan limit -> estimate(limit.child());
      case JoinPlan join ->
          estimate(join.left())
              + Math.ceil(rows(join.left()) / JoinPlan.BNLJ_BLOCK_ROWS) * estimate(join.right())
              + join.condition().map(this::subqueryCost).orElse(0.0);
      case InsertPlan insert -> 1;
      case UpdatePlan update ->
          estimate(update.source())
              + rows(update.source())
              + update.assignments().stream()
                  .mapToDouble(assignment -> subqueryCost(assignment.expression()))
                  .sum();
      case DeletePlan delete -> estimate(delete.source()) + rows(delete.source());
      case CreateIndexPlan create -> stats(create.table()).blockCount();
      // DDL rewrites catalog metadata only; one write each, like a single-row INSERT.
      case CreateTablePlan create -> 1;
      case DropTablePlan drop -> 1;
      case TransactionCommandPlan command -> 0;
    };
  }

  public long estimateRows(PlanNode plan) {
    return Math.round(rows(plan));
  }

  private double rows(PlanNode plan) {
    return switch (plan) {
      case TableScanPlan scan -> stats(scan.table()).rowCount();
      case IndexScanPlan scan ->
          indexRows(scan) * scan.residual().map(r -> selectivity(r, scan)).orElse(1.0);
      case FilterPlan filter ->
          rows(filter.child()) * selectivity(filter.predicate(), filter.child());
      case ProjectPlan project -> rows(project.child());
      case AggregatePlan aggregate -> aggregateRows(aggregate);
      case SortPlan sort -> rows(sort.child());
      case LimitPlan limit -> Math.min(limit.count(), rows(limit.child()));
      case JoinPlan join -> rows(join.left()) * rows(join.right()) * joinSelectivity(join);
      case InsertPlan insert -> 1;
      case UpdatePlan update -> rows(update.source());
      case DeletePlan delete -> rows(delete.source());
      case CreateIndexPlan create -> 0;
      case CreateTablePlan create -> 0;
      case DropTablePlan drop -> 0;
      case TransactionCommandPlan command -> 0;
    };
  }

  private double indexRows(IndexScanPlan scan) {
    return stats(scan.table()).rowCount() * selectivity(indexCondition(scan), scan);
  }

  private double joinSelectivity(JoinPlan join) {
    return switch (join.kind()) {
      case CARTESIAN -> 1.0;
      case THETA -> join.condition().map(c -> selectivity(c, join)).orElse(1.0);
      case NATURAL -> {
        Map<String, Long> right = distinctByColumn(join.right());
        yield distinctByColumn(join.left()).entrySet().stream()
            .filter(e -> right.containsKey(e.getKey()))
            .mapToDouble(e -> 1.0 / Math.max(e.getValue(), right.get(e.getKey())))
            .reduce(1.0, (a, b) -> a * b);
      }
    };
  }

  private double aggregateRows(AggregatePlan aggregate) {
    double inputRows = rows(aggregate.child());
    if (aggregate.groupBy().isEmpty()) return 1.0;
    if (inputRows == 0) return 0.0;
    double groups = 1.0;
    for (ColumnRef column : aggregate.groupBy()) {
      groups *= distinct(new ColumnExpression(column), aggregate.child());
      if (groups >= inputRows) return inputRows;
    }
    return Math.min(inputRows, groups);
  }

  /**
   * QP runs each uncorrelated subquery once per query and reuses the result for every row.
   * shortcut: a subquery filter on a BNLJ inner side is counted again on every rescan although QP
   * runs it once; split scan and subquery costs if such plans need exact numbers.
   */
  private double subqueryCost(Expression expression) {
    return switch (expression) {
      case InSubqueryExpression in ->
          subqueryCost(in.operand()) + in.plan().map(this::estimate).orElse(0.0);
      case ExistsSubqueryExpression exists -> exists.plan().map(this::estimate).orElse(0.0);
      case ScalarSubqueryExpression scalar -> scalar.plan().map(this::estimate).orElse(0.0);
      case BinaryExpression binary -> subqueryCost(binary.left()) + subqueryCost(binary.right());
      case UnaryExpression unary -> subqueryCost(unary.operand());
      default -> 0.0;
    };
  }

  private double selectivity(Expression predicate, PlanNode scope) {
    if (predicate instanceof UnaryExpression unary && unary.operator() == UnaryOp.NOT) {
      return 1.0 - selectivity(unary.operand(), scope);
    }
    if (!(predicate instanceof BinaryExpression binary)) return 1.0;
    double equal = 1.0 / Math.max(distinct(binary.left(), scope), distinct(binary.right(), scope));
    return switch (binary.operator()) {
      case AND -> selectivity(binary.left(), scope) * selectivity(binary.right(), scope);
      case OR ->
          1.0
              - (1.0 - selectivity(binary.left(), scope))
                  * (1.0 - selectivity(binary.right(), scope));
      case EQ -> equal;
      case NEQ -> 1.0 - equal;

      case IS -> equal;
      case IS_NOT -> 1.0 - equal;
      case LT, LTE, GT, GTE -> RANGE_SELECTIVITY;
      case ADD, SUB, MUL, DIV -> 1.0;
    };
  }

  private long distinct(Expression expression, PlanNode scope) {
    if (!(expression instanceof ColumnExpression column)) return 1;
    ColumnRef ref = column.column();
    return ref.relation()
        .map(alias -> scans(scope).get(alias))
        .map(table -> Math.max(1, stats(table).distinctCounts().getOrDefault(ref.column(), 1L)))
        .orElse(1L);
  }

  private Map<String, Long> distinctByColumn(PlanNode plan) {
    Map<String, Long> distinct = new HashMap<>();
    for (String table : scans(plan).values()) {
      Map<String, Long> counts = stats(table).distinctCounts();
      for (Column column : catalog.getSchema(table).columns()) {
        distinct.merge(
            column.name(), Math.max(1, counts.getOrDefault(column.name(), 1L)), Math::max);
      }
    }
    return distinct;
  }

  private Expression indexCondition(IndexScanPlan scan) {
    String column =
        catalog.listIndexes(scan.table()).stream()
            .filter(index -> index.name().equals(scan.index()))
            .map(IndexMetadata::column)
            .findFirst()
            .orElseThrow(
                () -> new DbmsException(ErrorCode.INVALID_PLAN, "Unknown index: " + scan.index()));
    return new BinaryExpression(
        new ColumnExpression(new ColumnRef(Optional.of(scan.alias()), column)),
        BinaryOp.valueOf(scan.predicate().operator().name()),
        new LiteralExpression(scan.predicate().value()));
  }

  public static Map<String, String> scans(PlanNode plan) {
    return switch (plan) {
      case TableScanPlan scan -> Map.of(scan.alias(), scan.table());
      case IndexScanPlan scan -> Map.of(scan.alias(), scan.table());
      case FilterPlan filter -> scans(filter.child());
      case ProjectPlan project -> scans(project.child());
      case AggregatePlan aggregate -> scans(aggregate.child());
      case SortPlan sort -> scans(sort.child());
      case LimitPlan limit -> scans(limit.child());
      case JoinPlan join -> {
        Map<String, String> all = new HashMap<>(scans(join.left()));
        all.putAll(scans(join.right()));
        yield all;
      }
      default -> Map.of();
    };
  }

  private TableStatistics stats(String table) {
    return catalog.getStatistics(table);
  }
}
