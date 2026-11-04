package dynamitedb.qo.binding;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Mengestimasi biaya (blok I/O) dan kardinalitas plan dari statistik katalog (QO-08). */
public class CostEstimator {

  // Matches QP's BlockNestedLoopJoinOperator, which rescans the inner side per 100 outer rows.
  private static final int JOIN_BLOCK_ROWS = 100;
  private static final double RANGE_SELECTIVITY = 1.0 / 3;

  private final CatalogAccess catalog;

  public CostEstimator(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  public double estimate(PlanNode plan) {
    return switch (plan) {
      case TableScanPlan scan -> stats(scan.table()).blockCount();
      // shortcut: one I/O per index probe and per matching row; use index height and
      // clustering once the catalog exposes them.
      case IndexScanPlan scan -> 1 + rows(scan);
      case FilterPlan filter -> estimate(filter.child());
      case ProjectPlan project -> estimate(project.child());
      // QP sorts in memory, so sorting adds no I/O.
      case SortPlan sort -> estimate(sort.child());
      case JoinPlan join ->
          estimate(join.left())
              + Math.ceil(rows(join.left()) / JOIN_BLOCK_ROWS) * estimate(join.right());
      case InsertPlan insert -> 1;
      case UpdatePlan update -> estimate(update.source()) + rows(update.source());
      case DeletePlan delete -> estimate(delete.source()) + rows(delete.source());
      case CreateIndexPlan create -> stats(create.table()).blockCount();
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
          stats(scan.table()).rowCount()
              * selectivity(indexCondition(scan), scan)
              * scan.residual().map(r -> selectivity(r, scan)).orElse(1.0);
      case FilterPlan filter ->
          rows(filter.child()) * selectivity(filter.predicate(), filter.child());
      case ProjectPlan project -> rows(project.child());
      case SortPlan sort -> rows(sort.child());
      case JoinPlan join -> rows(join.left()) * rows(join.right()) * joinSelectivity(join);
      case InsertPlan insert -> 1;
      case UpdatePlan update -> rows(update.source());
      case DeletePlan delete -> rows(delete.source());
      case CreateIndexPlan create -> 0;
      case TransactionCommandPlan command -> 0;
    };
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

  private double selectivity(Expression predicate, PlanNode scope) {
    if (!(predicate instanceof BinaryExpression binary)) return 1.0;
    double equal = 1.0 / Math.max(distinct(binary.left(), scope), distinct(binary.right(), scope));
    return switch (binary.operator()) {
      case AND -> selectivity(binary.left(), scope) * selectivity(binary.right(), scope);
      case EQ -> equal;
      case NEQ -> 1.0 - equal;
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

  /** Maps every relation alias scanned under {@code plan} to its table. */
  private static Map<String, String> scans(PlanNode plan) {
    return switch (plan) {
      case TableScanPlan scan -> Map.of(scan.alias(), scan.table());
      case IndexScanPlan scan -> Map.of(scan.alias(), scan.table());
      case FilterPlan filter -> scans(filter.child());
      case ProjectPlan project -> scans(project.child());
      case SortPlan sort -> scans(sort.child());
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
