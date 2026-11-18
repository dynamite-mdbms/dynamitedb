package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.DropBehavior;

public record DropTablePlan(String table, DropBehavior behavior) implements PlanNode {}
