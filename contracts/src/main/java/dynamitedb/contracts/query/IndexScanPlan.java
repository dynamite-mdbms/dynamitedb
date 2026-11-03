package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.IndexPredicate;
import java.util.Optional;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record IndexScanPlan(
    String index,
    String table,
    String alias,
    IndexPredicate predicate,
    Optional<Expression> residual)
    implements PlanNode {}
