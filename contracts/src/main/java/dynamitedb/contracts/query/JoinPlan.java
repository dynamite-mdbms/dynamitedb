package dynamitedb.contracts.query;

import java.util.Optional;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record JoinPlan(PlanNode left, PlanNode right, JoinKind kind, Optional<Expression> condition)
    implements PlanNode {

  /** Outer rows QP's block nested loop join buffers per inner pass; QO's join cost uses it too. */
  public static final int BNLJ_BLOCK_ROWS = 100;
}
