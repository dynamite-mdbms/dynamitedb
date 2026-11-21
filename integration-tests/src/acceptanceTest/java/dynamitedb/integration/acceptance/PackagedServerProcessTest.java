package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedServerProcessTest {
  @TempDir Path directory;

  @Test
  void readsOnlyAValidBoundPortFromTheProductionReadinessLine() {
    assertEquals(
        7777,
        PackagedServerProcess.parseReadyPort(
            "Persistent DynamiteDB runtime listening on 127.0.0.1:7777"));
    assertEquals(-1, PackagedServerProcess.parseReadyPort("server starting"));
    assertEquals(
        -1,
        PackagedServerProcess.parseReadyPort(
            "Persistent DynamiteDB runtime listening on 127.0.0.1:0"));
    assertEquals(
        -1,
        PackagedServerProcess.parseReadyPort(
            "Persistent DynamiteDB runtime listening on 127.0.0.1:999999999999999999999"));
  }

  @Test
  void stoppingTheHarnessReapsThePackagedServerChild() throws Exception {
    long pid;
    try (PackagedServerProcess server =
        PackagedServerProcess.start(directory.resolve("server-data"), "strict-2pl")) {
      pid = server.pid();
      assertTrue(server.isAlive());
    }
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }
}
