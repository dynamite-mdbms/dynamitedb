package dynamitedb.contracts.query;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record TableScanPlan(String table, String alias) implements PlanNode {}
