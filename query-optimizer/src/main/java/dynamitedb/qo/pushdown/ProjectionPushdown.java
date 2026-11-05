package dynamitedb.qo.pushdown;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.pipeline.PlanRewrite;

/** Membuang kolom yang tidak dibutuhkan sedini mungkin (QO-10). */
public class ProjectionPushdown implements PlanRewrite {

  @Override
  public PlanNode rewrite(PlanNode plan) {
    // TODO: sederhanakan proyeksi berantai dan dorong ke bawah join
    return plan;
  }
}
