package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Row;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record InsertPlan(String table, Row row) implements PlanNode {}
