package dynamitedb.qo.join;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.binding.CostEstimator;
import java.util.List;
import java.util.Objects;

/** Memilih kandidat plan dengan biaya terendah (QO-14). */
public class BestPlanSelector {

  private final CostEstimator cost;

  public BestPlanSelector(CostEstimator cost) {
    this.cost = Objects.requireNonNull(cost, "cost estimator");
  }

  public PlanNode choose(List<PlanNode> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      throw new DbmsException(ErrorCode.INVALID_PLAN, "No candidate plans to choose from");
    }
    PlanNode best = null;
    double bestCost = Double.POSITIVE_INFINITY;
    for (PlanNode candidate : candidates) {
      Objects.requireNonNull(candidate, "candidate plan");
      double candidateCost = cost.estimate(candidate);
      if (candidateCost < bestCost) {
        best = candidate;
        bestCost = candidateCost;
      }
    }
    if (best == null) {
      throw new DbmsException(ErrorCode.INVALID_PLAN, "Every candidate plan has an invalid cost");
    }
    return best;
  }
}
