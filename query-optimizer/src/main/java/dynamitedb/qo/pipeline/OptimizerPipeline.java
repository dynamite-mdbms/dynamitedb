package dynamitedb.qo.pipeline;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.JoinOrderOptimizer;
import java.util.List;

/** Menjalankan rewrite, enumerasi join, dan pemilihan plan (QO-14). */
public class OptimizerPipeline {

  private final List<PlanRewrite> rewrites;
  private final JoinOrderOptimizer joinEnumerator;
  private final BestPlanSelector selector;

  public OptimizerPipeline(
      List<PlanRewrite> rewrites, JoinOrderOptimizer joinEnumerator, BestPlanSelector selector) {
    this.rewrites = List.copyOf(rewrites);
    this.joinEnumerator = joinEnumerator;
    this.selector = selector;
  }

  public PlanNode optimize(PlanNode plan) {
    // TODO: terapkan rewrite, bangkitkan kandidat, lalu pilih yang termurah
    return plan;
  }
}
