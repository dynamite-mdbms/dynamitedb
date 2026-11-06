package dynamitedb.frm.driver;

import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Entry point driver FRM; skenario detailnya ada di {@link DriverScenarios}. */
public final class RealRecoveryDriver {
  private final Path root;

  public RealRecoveryDriver(Path root) {
    this.root = Objects.requireNonNull(root, "root");
  }

  public TestReport runAll() {
    return new TestReport(
        List.of(
            runScenario("wal"),
            runScenario("log-inspection"),
            runScenario("rollback"),
            runScenario("checkpoint"),
            runScenario("multi-transaction"),
            runScenario("mixed-dml"),
            runScenario("checkpoint-recovery"),
            runScenario("restart-twice"),
            runScenario("crash"),
            runScenario("historical")));
  }

  public TestResult runScenario(String name) {
    try {
      DriverScenarios scenarios = new DriverScenarios(root);
      return switch (name) {
        case "wal" -> scenarios.wal();
        case "log-inspection" -> scenarios.logInspection();
        case "rollback" -> scenarios.rollback();
        case "checkpoint" -> scenarios.checkpoint();
        case "multi-transaction" -> scenarios.multiTransaction();
        case "mixed-dml" -> scenarios.mixedDml();
        case "checkpoint-recovery" -> scenarios.checkpointRecovery();
        case "restart-twice" -> scenarios.restartTwice();
        case "crash" -> scenarios.crash();
        case "historical" -> scenarios.historical();
        default -> new TestResult(false, "skenario nggak dikenal: " + name);
      };
    } catch (RuntimeException failure) {
      return new TestResult(false, name + ": " + message(failure));
    }
  }

  public static void main(String[] args) {
    if (args.length == 2 && args[0].equals("crash-child")) {
      CrashChild.run(Path.of(args[1]));
      return;
    }

    Path data = Path.of("build/frm-driver");
    String scenario = "all";
    for (int index = 0; index < args.length; index++) {
      switch (args[index]) {
        case "--data-dir" -> {
          if (++index >= args.length) throw new IllegalArgumentException("--data-dir butuh path");
          data = Path.of(args[index]);
        }
        case "--scenario" -> {
          if (++index >= args.length) throw new IllegalArgumentException("--scenario butuh nama");
          scenario = args[index];
        }
        default -> throw new IllegalArgumentException("opsi nggak dikenal: " + args[index]);
      }
    }

    RealRecoveryDriver driver = new RealRecoveryDriver(data);
    TestReport report =
        scenario.equals("all")
            ? driver.runAll()
            : new TestReport(List.of(driver.runScenario(scenario)));
    report
        .results()
        .forEach(
            result -> System.out.println((result.passed() ? "PASS " : "FAIL ") + result.message()));
    if (!report.passed()) System.exit(1);
  }

  private static String message(RuntimeException failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }
}
