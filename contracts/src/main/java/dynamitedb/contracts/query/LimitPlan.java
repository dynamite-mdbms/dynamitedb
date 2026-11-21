package dynamitedb.contracts.query;

/**
 * Emits at most {@code count} rows of {@code child}, in the child's order. QO places it above the
 * sort and the final projection, so it never changes which rows a sort or filter sees.
 */
public record LimitPlan(PlanNode child, long count) implements PlanNode {
  public LimitPlan {
    if (count < 0) throw new IllegalArgumentException("LIMIT must not be negative");
  }
}
