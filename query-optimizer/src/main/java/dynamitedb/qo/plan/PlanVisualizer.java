package dynamitedb.qo.plan;

import dynamitedb.contracts.query.PlanNode;

/** Menampilkan plan dalam bentuk pohon teks (QO-15). */
public class PlanVisualizer {

  public String render(PlanNode plan) {
    // TODO: teks berindentasi beserta parameter tiap node
    return plan.toString();
  }
}
