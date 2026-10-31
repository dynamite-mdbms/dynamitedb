package dynamitedb.sm.draft;

import java.util.List;

/** Temporary test-harness report shape; no scenarios are implemented by this record. */
public record TestReport(List<TestResult> results) {
  public TestReport {
    results = List.copyOf(results);
  }
}
