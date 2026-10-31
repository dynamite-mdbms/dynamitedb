package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Schema;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record PreparedQuery(PlanNode root, Schema outputSchema) {}
