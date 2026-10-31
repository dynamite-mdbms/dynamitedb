package dynamitedb.qo.pushdown;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.pipeline.PlanRewrite;

/** Mendorong seleksi sedekat mungkin ke tabel sumber (QO-09). */
public class SelectionPushdown implements PlanRewrite {

  @Override
  public PlanNode rewrite(PlanNode plan) {
    // TODO: pecah AND, tukar urutan seleksi, dan dorong ke bawah join
    return plan;
  }
}
