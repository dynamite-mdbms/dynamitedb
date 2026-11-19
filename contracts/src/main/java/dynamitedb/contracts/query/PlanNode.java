package dynamitedb.contracts.query;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public sealed interface PlanNode
    permits AggregatePlan,
        TableScanPlan,
        IndexScanPlan,
        FilterPlan,
        ProjectPlan,
        JoinPlan,
        SortPlan,
        InsertPlan,
        UpdatePlan,
        DeletePlan,
        CreateIndexPlan,
        TransactionCommandPlan {}
