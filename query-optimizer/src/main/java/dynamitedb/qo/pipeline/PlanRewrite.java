package dynamitedb.qo.pipeline;

import dynamitedb.contracts.query.PlanNode;

public interface PlanRewrite {

  PlanNode rewrite(PlanNode plan);
}
