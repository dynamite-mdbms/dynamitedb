package dynamitedb.contracts.query;

import java.util.Objects;
import java.util.Optional;

public record ExistsSubqueryExpression(
    SelectStatement query, Optional<PlanNode> plan, boolean negated) implements Expression {
  public ExistsSubqueryExpression {
    Objects.requireNonNull(query, "query");
    plan = Objects.requireNonNull(plan, "plan");
  }
}
