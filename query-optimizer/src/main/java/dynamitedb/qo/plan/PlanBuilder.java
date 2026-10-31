package dynamitedb.qo.plan;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableScanPlan;

/** Mengubah statement hasil binding menjadi PlanNode (QO-02, QO-03). */
public class PlanBuilder {

  public PlanNode build(Statement bound) {
    // TODO: pemetaan tiap jenis Statement ke varian PlanNode
    return new TableScanPlan("mock", "mock");
  }
}
