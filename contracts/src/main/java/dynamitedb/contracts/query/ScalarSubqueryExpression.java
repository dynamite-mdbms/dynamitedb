package dynamitedb.contracts.query;

import java.util.Objects;
import java.util.Optional;


public record ScalarSubqueryExpression(SelectStatement query, Optional<PlanNode> plan)
    implements Expression {
  public ScalarSubqueryExpression {
    Objects.requireNonNull(query, "query");
    plan = Objects.requireNonNull(plan, "plan");
  }
}
