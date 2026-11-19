package dynamitedb.qo.pushdown;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregatePlan;
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

public class ProjectionPushdown implements PlanRewrite {

  private final CatalogAccess catalog;

  public ProjectionPushdown(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  @Override
  public PlanNode rewrite(PlanNode plan) {
    if (!(plan instanceof ProjectPlan project)) return plan;
    PlanNode child = project.child();

    while (child instanceof ProjectPlan inner) child = inner.child();
    Set<ColumnRef> needed =
        project.items().stream()
            .flatMap(item -> SelectionPushdown.columns(item.expression()).stream())
            .collect(Collectors.toSet());
    return ProjectPlan.fromItems(prune(child, Set.copyOf(needed)), project.items());
  }

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

      case ProjectPlan project -> prune(project.child(), needed);
      case AggregatePlan aggregate -> {
        Set<ColumnRef> input = with(needed, aggregate.groupBy());
        for (AggregateExpression expression : aggregate.aggregates()) {
          expression.argument().ifPresent(column -> input.add(column));
        }
        yield new AggregatePlan(
            prune(aggregate.child(), input), aggregate.groupBy(), aggregate.aggregates());
      }
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

  private PlanNode input(PlanNode side, Set<ColumnRef> needed) {
    PlanNode pruned = prune(side, needed);

    if (!readsOneTable(pruned) || needed.stream().anyMatch(c -> c.relation().isEmpty())) {
      return pruned;
    }
    List<ColumnRef> available = tableColumns(pruned);
    List<ColumnRef> kept = available.stream().filter(needed::contains).toList();
    if (kept.isEmpty() || kept.size() == available.size()) return pruned;
    return new ProjectPlan(pruned, kept);
  }

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

  private static String name(ColumnRef column) {
    return column.column().toLowerCase(Locale.ROOT);
  }

  private static Set<ColumnRef> with(Set<ColumnRef> needed, Collection<ColumnRef> more) {
    Set<ColumnRef> all = new HashSet<>(needed);
    all.addAll(more);
    return all;
  }
}
