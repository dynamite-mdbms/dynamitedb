package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Row;

// draf dari QP biar bisa compile, nanti ganti punya QO
public record InsertPlan(String table, Row row) implements PlanNode {}
