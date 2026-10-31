package dynamitedb.ccm.driver;

import dynamitedb.ccm.timestamp.Schedule;
import dynamitedb.contracts.testing.TestReport;

public abstract class ConcurrencyTestDriver {
  public abstract TestReport run(Schedule schedule);
}
