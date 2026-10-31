package dynamitedb.contracts.query;

// draf dari QP biar bisa compile, nanti ganti punya QO
// source = plan scan/filter yang menghasilkan baris yang mau dihapus
public record DeletePlan(PlanNode source, String table) implements PlanNode {}
