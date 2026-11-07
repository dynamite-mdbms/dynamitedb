package dynamitedb.qo.pushdown;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.pipeline.PlanRewrite;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Membuang kolom yang tidak dibutuhkan sedini mungkin (QO-10).
 *
 * <p>The outermost projection is kept as written, so the SELECT column order never changes. Each
 * table under a join gets a projection holding only the columns read above it: the final result,
 * join conditions, selections and ORDER BY.
 */
public class ProjectionPushdown implements PlanRewrite {

  private final CatalogAccess catalog;

  public ProjectionPushdown(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  @Override
  public PlanNode rewrite(PlanNode plan) {
    if (!(plan instanceof ProjectPlan project)) return plan;
    PlanNode child = project.child();
    // An inner projection must already contain every outer column, so only the outermost matters.
    while (child instanceof ProjectPlan inner) child = inner.child();
    return new ProjectPlan(prune(child, Set.copyOf(project.columns())), project.columns());
  }

  /** Rebuilds {@code node} so every table under a join carries only {@code needed} columns. */
  private PlanNode prune(PlanNode node, Set<ColumnRef> needed) {
    return switch (node) {
      case FilterPlan filter ->
          new FilterPlan(
              prune(filter.child(), with(needed, SelectionPushdown.columns(filter.predicate()))),
              filter.predicate());
      case SortPlan sort ->
          new SortPlan(
              prune(sort.child(), with(needed, sort.keys().stream().map(SortKey::column).toList())),
              sort.keys());
      // A projection placed by an earlier pass is rebuilt from the current needs.
      case ProjectPlan project -> prune(project.child(), needed);
      case JoinPlan join -> {
        Set<ColumnRef> all =
            with(needed, join.condition().map(SelectionPushdown::columns).orElse(List.of()));
        if (join.kind() == JoinKind.NATURAL) all = with(all, sharedColumns(join));
        yield new JoinPlan(
            input(join.left(), all), input(join.right(), all), join.kind(), join.condition());
      }
      default -> node;
    };
  }

  /** Prunes one join input and, when it reads a single table, projects that table. */
  private PlanNode input(PlanNode side, Set<ColumnRef> needed) {
    PlanNode pruned = prune(side, needed);
    // Unbound columns (e.g. the driver's stub binder) cannot be traced to a table: keep them all.
    if (!readsOneTable(pruned) || needed.stream().anyMatch(c -> c.relation().isEmpty())) {
      return pruned;
    }
    List<ColumnRef> available = tableColumns(pruned);
    List<ColumnRef> kept = available.stream().filter(needed::contains).toList();
    // shortcut: a table that feeds no column keeps all of them, because a zero-column row may
    // not be supported by QP; project it to one column once QP confirms empty rows work.
    if (kept.isEmpty() || kept.size() == available.size()) return pruned;
    return new ProjectPlan(pruned, kept);
  }

  /**
   * NATURAL JOIN matches on every column name both sides share, even though the plan names none of
   * them. Like PostgreSQL and Calcite, which expand NATURAL into equalities on the shared columns,
   * those columns count as join-condition columns on both sides.
   */
  private List<ColumnRef> sharedColumns(JoinPlan join) {
    List<ColumnRef> left = tableColumns(join.left());
    List<ColumnRef> right = tableColumns(join.right());
    Set<String> rightNames =
        right.stream().map(ProjectionPushdown::name).collect(Collectors.toSet());
    Set<String> shared =
        left.stream()
            .map(ProjectionPushdown::name)
            .filter(rightNames::contains)
            .collect(Collectors.toSet());
    return Stream.concat(left.stream(), right.stream())
        .filter(c -> shared.contains(name(c)))
        .toList();
  }

  /** Every column of every table scanned under {@code node}, qualified by its alias. */
  private List<ColumnRef> tableColumns(PlanNode node) {
    return CostEstimator.scans(node).entrySet().stream()
        .flatMap(
            scan ->
                catalog.getSchema(scan.getValue()).columns().stream()
                    .map(c -> new ColumnRef(Optional.of(scan.getKey()), c.name())))
        .toList();
  }

  private static boolean readsOneTable(PlanNode node) {
    return switch (node) {
      case TableScanPlan scan -> true;
      case IndexScanPlan scan -> true;
      case FilterPlan filter -> readsOneTable(filter.child());
      default -> false;
    };
  }

  // Binder matches NATURAL JOIN columns case-insensitively, so this does too.
  private static String name(ColumnRef column) {
    return column.column().toLowerCase(Locale.ROOT);
  }

  private static Set<ColumnRef> with(Set<ColumnRef> needed, Collection<ColumnRef> more) {
    Set<ColumnRef> all = new HashSet<>(needed);
    all.addAll(more);
    return all;
  }
}
