package dynamitedb.qp.driver;

import dynamitedb.contracts.api.QueryProcessor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Standalone SQL driver using only QP's public boundary, independently of the network CLI. */
public final class QueryProcessorTestDriver {
  private final QueryProcessor processor;

  public QueryProcessorTestDriver(QueryProcessor processor) {
    this.processor = Objects.requireNonNull(processor, "processor");
  }

  public record Scenario(
      String name,
      String sql,
      Optional<ErrorCode> expectedError,
      Predicate<QueryResult> expectation) {
    public Scenario {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(sql, "sql");
      Objects.requireNonNull(expectedError, "expectedError");
      Objects.requireNonNull(expectation, "expectation");
    }
  }

  public TestReport runSuite(List<String> queries) {
    return runScenarios(
        queries.stream()
            .map(sql -> new Scenario(sql, sql, Optional.empty(), result -> true))
            .toList());
  }

  public TestReport runScenarios(List<Scenario> scenarios) {
    List<TestResult> results = new ArrayList<>();
    SessionId session = processor.openSession("qp-test-driver");
    try {
      for (Scenario scenario : scenarios) {
        try {
          QueryResult result = processor.execute(session, scenario.sql());
          boolean passed =
              scenario.expectedError().isEmpty() && scenario.expectation().test(result);
          results.add(
              new TestResult(
                  passed,
                  scenario.name()
                      + ": actual rows="
                      + result.rows().size()
                      + ", affected="
                      + result.affectedRows()
                      + ", message="
                      + result.message()));
        } catch (DbmsException failure) {
          results.add(
              new TestResult(
                  scenario.expectedError().filter(code -> code == failure.getCode()).isPresent(),
                  scenario.name()
                      + ": actual error="
                      + failure.getCode()
                      + ": "
                      + failure.getMessage()));
        } catch (RuntimeException failure) {
          results.add(new TestResult(false, scenario.name() + ": unexpected execution failure"));
        }
      }
    } finally {
      try {
        processor.closeSession(session);
      } catch (RuntimeException failure) {
        results.add(new TestResult(false, "Session cleanup failed: " + failure.getMessage()));
      }
    }
    return new TestReport(results);
  }
}
