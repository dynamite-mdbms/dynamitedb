package dynamitedb.qo.verification;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import java.util.ArrayList;
import java.util.List;

/** Menguji QO lewat port QueryOptimizer tanpa Storage Manager asli (QO-16). */
public class QueryOptimizerDriver {

  private final QueryOptimizer optimizer;

  public QueryOptimizerDriver(QueryOptimizer optimizer) {
    this.optimizer = optimizer;
  }

  public TestReport runSuite(List<String> queries) {
    List<TestResult> results = new ArrayList<>();
    for (String query : queries) {
      try {
        var explanation = optimizer.explain(query);
        boolean complete =
            explanation != null
                && explanation.before() != null
                && explanation.after() != null
                && !Double.isNaN(explanation.cost());
        results.add(
            new TestResult(
                complete,
                complete
                    ? "PASS: " + query + System.lineSeparator() + explanation.after()
                    : "FAIL: incomplete explanation for " + query));
      } catch (DbmsException handled) {
        results.add(
            new TestResult(
                true,
                "HANDLED " + handled.getCode() + ": " + query + " -> " + handled.getMessage()));
      } catch (RuntimeException unexpected) {
        results.add(
            new TestResult(
                false,
                "FAIL: "
                    + query
                    + " -> "
                    + unexpected.getClass().getSimpleName()
                    + ": "
                    + unexpected.getMessage()));
      }
    }
    return new TestReport(results);
  }
}
