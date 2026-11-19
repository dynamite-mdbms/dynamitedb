package dynamitedb.qo.pipeline;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.GeneticJoinOrderOptimizer;
import dynamitedb.qo.join.JoinOrderOptimizer;
import java.util.List;
import java.util.Objects;


public class OptimizerPipeline {

  private final List<PlanRewrite> rewrites;
  private final JoinOrderOptimizer joinEnumerator;
  private final BestPlanSelector selector;
  private final GeneticJoinOrderOptimizer geneticJoinOrderOptimizer;

  public OptimizerPipeline(
      List<PlanRewrite> rewrites, JoinOrderOptimizer joinEnumerator, BestPlanSelector selector) {
    this(rewrites, joinEnumerator, selector, null);
  }


  public OptimizerPipeline(
      List<PlanRewrite> rewrites,
      JoinOrderOptimizer joinEnumerator,
      BestPlanSelector selector,
      GeneticJoinOrderOptimizer geneticJoinOrderOptimizer) {
    this.rewrites = List.copyOf(rewrites);
    this.joinEnumerator = Objects.requireNonNull(joinEnumerator, "joinEnumerator");
    this.selector = Objects.requireNonNull(selector, "selector");
    this.geneticJoinOrderOptimizer = geneticJoinOrderOptimizer;
  }

  public PlanNode optimize(PlanNode plan) {
    PlanNode rewritten = plan;
    for (PlanRewrite rewrite : rewrites) rewritten = rewrite.rewrite(rewritten);
    if (geneticJoinOrderOptimizer != null) {
      return geneticJoinOrderOptimizer.optimize(rewritten).bestPlan();
    }
    List<PlanNode> candidates = joinEnumerator.enumerate(rewritten);
    return candidates.isEmpty() ? rewritten : selector.choose(candidates);
  }
}
