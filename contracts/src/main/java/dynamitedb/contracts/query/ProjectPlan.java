package dynamitedb.contracts.query;

import java.util.List;

public record ProjectPlan(PlanNode child, List<ColumnRef> columns) implements PlanNode {

  public ProjectPlan {
    columns = List.copyOf(columns);
  }
}
