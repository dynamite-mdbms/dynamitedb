package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.IndexKind;

// draf dari QP biar bisa compile, nanti ganti punya QO
public record CreateIndexPlan(String index, String table, String column, IndexKind kind)
    implements PlanNode {}
