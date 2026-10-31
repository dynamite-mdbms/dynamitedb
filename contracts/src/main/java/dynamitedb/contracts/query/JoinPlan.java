package dynamitedb.contracts.query;

import java.util.Optional;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record JoinPlan(PlanNode left, PlanNode right, JoinKind kind, Optional<Expression> condition)
    implements PlanNode {}
