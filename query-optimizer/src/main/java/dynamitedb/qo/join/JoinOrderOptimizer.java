package dynamitedb.qo.join;

import dynamitedb.contracts.query.PlanNode;
import java.util.List;

/** Membangkitkan kandidat urutan dan pengelompokan join (QO-12). */
public class JoinOrderOptimizer {

  public List<PlanNode> enumerate(PlanNode plan) {
    // TODO: kandidat dari sifat komutatif dan asosiatif join
    return List.of(plan);
  }
}
