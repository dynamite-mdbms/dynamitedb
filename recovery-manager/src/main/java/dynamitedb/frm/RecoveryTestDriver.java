package dynamitedb.frm;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import dynamitedb.frm.mock.MockRecoveryEnvironment;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Standalone contract smoke driver. Results describe mocks, never real crash/durability tests. */
public final class RecoveryTestDriver {
  private static final List<String> SCENARIOS =
      List.of(
          "wal",
          "log-inspection",
          "rollback",
          "checkpoint",
          "historical",
          "startup",
          "initialize-new",
          "open-existing");
  private final MockRecoveryEnvironment environment;

  public RecoveryTestDriver() {
    this(MockRecoveryEnvironment.create());
  }

  public RecoveryTestDriver(MockRecoveryEnvironment environment) {
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  public TestReport runAll() {
    return new TestReport(SCENARIOS.stream().map(this::runScenario).toList());
  }

  public TestResult runScenario(String name) {
    Objects.requireNonNull(name, "name");
    var c = environment.context();
    var recovery = environment.recovery();
    try {
      switch (name) {
        case "wal" -> {
          recovery.begin(c.tx(), c.highWater().maxTimestamp());
          Objects.requireNonNull(recovery.appendChange(c.change()));
          recovery.ensureDurable(c.lsn());
          recovery.commitDurably(c.tx());
        }
        case "log-inspection" ->
            Objects.requireNonNull(
                recovery.readLog(new LogFilter(Optional.empty(), Optional.empty())));
        case "rollback" -> recovery.rollbackCompletely(c.tx());
        case "checkpoint" -> Objects.requireNonNull(recovery.checkpoint());
        case "historical" ->
            requireSuccess(
                recovery.recoverTo(
                    new RecoveryTarget(Optional.of(c.commitTime()), Optional.empty())));
        case "startup" -> requireSuccess(recovery.recover());
        case "initialize-new" -> requireSuccess(environment.initializer().createNew(c.snapshot()));
        case "open-existing" -> requireSuccess(environment.initializer().openExisting());
        default -> {
          return new TestResult(false, "MOCK ONLY: unknown scenario: " + name);
        }
      }
      return new TestResult(
          true, "MOCK ONLY: " + name + " contract call passed; no persistent DBMS behavior tested");
    } catch (RuntimeException failure) {
      return new TestResult(false, "MOCK ONLY: " + name + " failed: " + failure.getMessage());
    }
  }

  private static void requireSuccess(RecoveryResult result) {
    Objects.requireNonNull(result, "result");
    if (result.status() != RecoveryStatus.SUCCESS) {
      throw new DbmsException(ErrorCode.RECOVERY_FAILURE, result.message());
    }
  }

  public static void main(String[] args) {
    var driver = new RecoveryTestDriver();
    var report =
        args.length == 0 || args[0].equals("all")
            ? driver.runAll()
            : new TestReport(List.of(driver.runScenario(args[0])));
    report
        .results()
        .forEach(r -> System.out.println((r.passed() ? "PASS " : "FAIL ") + r.message()));
    if (report.results().stream().anyMatch(r -> !r.passed())) {
      System.exit(1);
    }
  }
}
