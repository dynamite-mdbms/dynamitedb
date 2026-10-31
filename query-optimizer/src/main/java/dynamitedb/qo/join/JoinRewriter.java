package dynamitedb.qo.join;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.pipeline.PlanRewrite;

/** Menggabungkan seleksi dengan cartesian product atau join di bawahnya (QO-11). */
public class JoinRewriter implements PlanRewrite {

  @Override
  public PlanNode rewrite(PlanNode plan) {
    // TODO: ubah cartesian product + seleksi menjadi theta join
    return plan;
  }
}
