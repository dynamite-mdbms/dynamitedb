package dynamitedb.contracts.api;

import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;

public interface QueryOptimizer {

  PreparedQuery prepare(String sql);

  double estimateCost(PlanNode plan);

  PlanExplanation explain(String sql);
}
