package dynamitedb.qo.join;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.binding.CostEstimator;
import java.util.List;

/** Memilih kandidat plan dengan biaya terendah (QO-14). */
public class BestPlanSelector {

  private final CostEstimator cost;

  public BestPlanSelector(CostEstimator cost) {
    this.cost = cost;
  }

  public PlanNode choose(List<PlanNode> candidates) {
    // TODO: bandingkan kandidat lewat CostEstimator
    return candidates.get(0);
  }
}
