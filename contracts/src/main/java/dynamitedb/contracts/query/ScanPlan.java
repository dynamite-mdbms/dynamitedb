package dynamitedb.contracts.query;

// draf dari QP biar bisa compile, nanti ganti punya QO
public record ScanPlan(String table) implements PlanNode {}
