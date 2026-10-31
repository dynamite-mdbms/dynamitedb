package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.IndexKind;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record CreateIndexPlan(String index, String table, String column, IndexKind kind)
    implements PlanNode {}
