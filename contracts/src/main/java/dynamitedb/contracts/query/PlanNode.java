package dynamitedb.contracts.query;

// draf dari QP biar bisa compile, nanti ganti punya QO
// TODO: varian Project, Sort, IndexScan nunggu QO
public sealed interface PlanNode
    permits ScanPlan,
        FilterPlan,
        JoinPlan,
        InsertPlan,
        UpdatePlan,
        DeletePlan,
        CreateIndexPlan,
        TransactionCommandPlan {}
