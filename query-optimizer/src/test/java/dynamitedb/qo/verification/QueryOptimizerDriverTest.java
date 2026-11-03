package dynamitedb.qo.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryOptimizerDriverTest {

  @Test
  void splitsMultilineScriptWithoutBreakingQuotedSemicolon() {
    String script =
        """
        # ignored comment
        -- expect: OK
        SELECT 'a;b'
        FROM student;

        -- expect: SQL_SYNTAX
        SELEC * FROM student;
        """;

    List<String> scenarios = QueryOptimizerDriver.splitSqlScript(script);

    assertEquals(2, scenarios.size());
    assertTrue(scenarios.get(0).contains("'a;b'"));
    assertTrue(scenarios.get(1).startsWith("-- expect: SQL_SYNTAX"));
  }

  @Test
  void failsScenarioWhenExpectedErrorDoesNotOccur() {
    QueryOptimizer alwaysSucceeds =
        new QueryOptimizer() {
          @Override
          public PreparedQuery prepare(String sql) {
            throw new UnsupportedOperationException();
          }

          @Override
          public double estimateCost(PlanNode plan) {
            return 1;
          }

          @Override
          public PlanExplanation explain(String sql) {
            return new PlanExplanation("before", "after", 1);
          }
        };

    var report =
        new QueryOptimizerDriver(alwaysSucceeds)
            .runSuite(List.of("-- expect: SQL_SYNTAX\nSELECT * FROM student;"));

    assertFalse(report.passed());
    assertTrue(report.results().get(0).message().contains("expected SQL_SYNTAX"));
  }
}
