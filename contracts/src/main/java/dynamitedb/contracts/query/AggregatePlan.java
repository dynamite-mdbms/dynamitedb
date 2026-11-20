package dynamitedb.contracts.query;

import java.util.List;
import java.util.Objects;

public record AggregatePlan(
    PlanNode child, List<ColumnRef> groupBy, List<AggregateExpression> aggregates)
    implements PlanNode {
  public AggregatePlan {
    Objects.requireNonNull(child, "child");
    groupBy = List.copyOf(groupBy);
    aggregates = List.copyOf(aggregates);
  }
}
