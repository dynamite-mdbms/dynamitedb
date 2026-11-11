package dynamitedb.frm.driver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealRecoveryDriverTest {
  @TempDir Path directory;

  @Test
  void allFileBackedScenariosPass() {
    var report = new RealRecoveryDriver(directory).runAll();
    assertTrue(report.passed(), report.results().toString());
  }
}
