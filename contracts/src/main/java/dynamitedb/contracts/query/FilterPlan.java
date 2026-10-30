package dynamitedb.contracts.query;

// draf dari QP biar bisa compile, nanti ganti punya QO
public record FilterPlan(PlanNode child, Expression predicate) implements PlanNode {}
