package dynamitedb.contracts.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public final class ProjectPlan implements PlanNode {
  private final PlanNode child;
  private final List<SelectItem> items;

  public ProjectPlan(PlanNode child, List<ColumnRef> columns) {
    this(
        child,
        columns.stream()
            .map(column -> new SelectItem(new ColumnExpression(column), Optional.empty()))
            .toList(),
        true);
  }

  private ProjectPlan(PlanNode child, List<SelectItem> items, boolean selectItems) {
    this.child = Objects.requireNonNull(child, "child");
    this.items = List.copyOf(items);
  }

  public static ProjectPlan fromItems(PlanNode child, List<SelectItem> items) {
    return new ProjectPlan(child, items, true);
  }

  public PlanNode child() {
    return child;
  }

  public List<SelectItem> items() {
    return items;
  }

  public List<ColumnRef> columns() {
    return java.util.stream.IntStream.range(0, items.size())
        .mapToObj(
            index -> {
              SelectItem item = items.get(index);
              if (item.expression() instanceof ColumnExpression column) return column.column();
              return new ColumnRef(Optional.empty(), item.alias().orElse("__select_item_" + index));
            })
        .toList();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof ProjectPlan project)) return false;
    return child.equals(project.child) && items.equals(project.items);
  }

  @Override
  public int hashCode() {
    return Objects.hash(child, items);
  }

  @Override
  public String toString() {
    return "ProjectPlan[child=" + child + ", items=" + items + "]";
  }
}
