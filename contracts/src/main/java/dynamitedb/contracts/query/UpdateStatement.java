package dynamitedb.contracts.query;

import java.util.List;
import java.util.Optional;

public record UpdateStatement(
    String table, List<UpdateAssignment> assignments, Optional<Expression> predicate)
    implements Statement {
  public UpdateStatement {
    assignments = List.copyOf(assignments);
    if (assignments.isEmpty())
      throw new IllegalArgumentException("UPDATE needs at least one assignment");
  }

  public UpdateStatement(
      String table, ColumnRef target, Expression expression, Optional<Expression> predicate) {
    this(table, List.of(new UpdateAssignment(target, expression)), predicate);
  }

  /** Compatibility accessors for callers that support only the original single-assignment form. */
  public ColumnRef target() {
    return assignments.get(0).target();
  }

  public Expression expression() {
    return assignments.get(0).expression();
  }
}
