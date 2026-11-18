package dynamitedb.contracts.query;

public record DropTablePlan(String table, DropBehavior behavior) implements PlanNode {}
