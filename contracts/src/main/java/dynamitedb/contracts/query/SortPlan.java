package dynamitedb.contracts.query;

import java.util.List;

public record SortPlan(PlanNode child, List<SortKey> keys) implements PlanNode {

  public SortPlan {
    keys = List.copyOf(keys);
  }
}
