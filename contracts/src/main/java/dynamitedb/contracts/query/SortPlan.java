package dynamitedb.contracts.query;

import java.util.List;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record SortPlan(PlanNode child, List<SortKey> keys) implements PlanNode {}
