package dynamitedb.contracts.query;

import java.util.List;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record UpdatePlan(PlanNode source, String table, List<UpdateAssignment> assignments)
    implements PlanNode {
  public UpdatePlan {
    assignments = List.copyOf(assignments);
    if (assignments.isEmpty()) throw new IllegalArgumentException("UPDATE needs an assignment");
  }

  public UpdatePlan(PlanNode source, String table, ColumnRef target, Expression value) {
    this(source, table, List.of(new UpdateAssignment(target, value)));
  }

  public ColumnRef target() {
    return assignments.get(0).target();
  }

  public Expression value() {
    return assignments.get(0).expression();
  }
}
