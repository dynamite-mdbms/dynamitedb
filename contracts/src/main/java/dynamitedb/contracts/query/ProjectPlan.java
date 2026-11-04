package dynamitedb.contracts.query;

import java.util.List;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record ProjectPlan(PlanNode child, List<ColumnRef> columns) implements PlanNode {
  public ProjectPlan {
    columns = List.copyOf(columns);
  }
}
