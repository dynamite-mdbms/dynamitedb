package dynamitedb.contracts.testing;

import java.util.List;

public record TestReport(List<TestResult> results) {

  public TestReport {
    results = List.copyOf(results);
  }
}
