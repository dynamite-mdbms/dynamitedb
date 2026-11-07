package dynamitedb.qo.join;

import static dynamitedb.qo.join.Qo5TestSupport.catalog;
import static dynamitedb.qo.join.Qo5TestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.binding.CostEstimator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BestPlanSelectorTest {

  @Test
  void choosesCandidateWithLowestCost() {
    PlanNode cheap = scan("student", "cheap");
    PlanNode expensive = scan("student", "expensive");
    BestPlanSelector selector = selector(plan -> plan == cheap ? 1.0 : 10.0);

    assertEquals(cheap, selector.choose(List.of(expensive, cheap)));
  }

  @Test
  void keepsFirstCandidateOnTiedCost() {
    PlanNode first = scan("student", "first");
    PlanNode second = scan("student", "second");
    BestPlanSelector selector = selector(plan -> 5.0);

    assertEquals(first, selector.choose(List.of(first, second)));
  }

  @Test
  void rejectsEmptyCandidateList() {
    BestPlanSelector selector = selector(plan -> 1.0);

    DbmsException exception = assertThrows(DbmsException.class, () -> selector.choose(List.of()));

    assertEquals(ErrorCode.INVALID_PLAN, exception.getCode());
  }

  @Test
  void rejectsNullCandidateList() {
    BestPlanSelector selector = selector(plan -> 1.0);

    DbmsException exception = assertThrows(DbmsException.class, () -> selector.choose(null));

    assertEquals(ErrorCode.INVALID_PLAN, exception.getCode());
  }

  private static BestPlanSelector selector(java.util.function.ToDoubleFunction<PlanNode> costs) {
    CostEstimator estimator =
        new CostEstimator(catalog(Map.of(), List.of())) {
          @Override
          public double estimate(PlanNode plan) {
            return costs.applyAsDouble(plan);
          }
        };
    return new BestPlanSelector(estimator);
  }
}
