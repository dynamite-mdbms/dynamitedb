package dynamitedb.contracts.testing;

import java.util.List;

public record TestReport(List<TestResult> results) {
  public TestReport {
    results = List.copyOf(results);
  }

  public boolean passed() {
    return results.stream().allMatch(TestResult::passed);
  }
}
