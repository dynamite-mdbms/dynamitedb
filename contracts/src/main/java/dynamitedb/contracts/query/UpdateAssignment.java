package dynamitedb.contracts.query;

import java.util.Objects;

public record UpdateAssignment(ColumnRef target, Expression expression) {
  public UpdateAssignment {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(expression, "expression");
  }
}
