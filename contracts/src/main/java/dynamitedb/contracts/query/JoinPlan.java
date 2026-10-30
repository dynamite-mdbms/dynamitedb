package dynamitedb.contracts.query;

// draf dari QP biar bisa compile, nanti ganti punya QO
public record JoinPlan(PlanNode left, PlanNode right, JoinType type, Expression condition)
    implements PlanNode {}
