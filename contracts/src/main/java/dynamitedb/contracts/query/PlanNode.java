package dynamitedb.contracts.query;

// draf dari QP biar bisa compile, nanti ganti punya QO
// TODO: varian Project, Sort, IndexScan, Insert, Update, Delete, CreateIndex nunggu QO
public sealed interface PlanNode permits ScanPlan, FilterPlan, JoinPlan {}
