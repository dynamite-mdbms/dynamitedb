package dynamitedb.ccm.driver;

import dynamitedb.ccm.timestamp.Schedule;
import dynamitedb.contracts.testing.TestReport;

public class ConcurrencyTestDriver {
  public TestReport run(Schedule schedule) {
    return new TestReport(java.util.List.of());
  }
}
