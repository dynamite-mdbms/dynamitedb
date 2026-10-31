package dynamitedb.contracts.query;

// draf dari QP biar bisa compile, nanti ganti punya QO
// source = plan scan/filter yang menghasilkan baris yang mau diubah
public record UpdatePlan(PlanNode source, String table, ColumnRef target, Expression value)
    implements PlanNode {}
