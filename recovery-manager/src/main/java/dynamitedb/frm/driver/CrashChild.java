package dynamitedb.frm.driver;

import java.nio.file.Path;

/** Child JVM buat skenario crash: nulis state lalu halt tanpa close. */
final class CrashChild {
  private CrashChild() {}

  static void run(Path directory) {
    RecoveryHarness harness =
        RecoveryHarness.fresh(directory.getParent(), directory.getFileName().toString());
    DriverScenarios.seedCrashState(harness);
    // sengaja: nggak close, nggak shutdown hook, biar mirip crash beneran
    Runtime.getRuntime().halt(0);
  }
}
