package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubRuntimeTest {
  @Test
  void composesActualModuleFacadesWithoutInitializationSideEffects(@TempDir Path directory) {
    Path data = directory.resolve("data");
    DbmsConfig defaults = DbmsConfig.defaults();
    StubRuntime runtime =
        new StubRuntime(new DbmsConfig(defaults.port(), data, defaults.protocol()));

    assertNotNull(runtime.processor());
    assertNotNull(runtime.optimizer());
    assertNotNull(runtime.storage());
    assertNotNull(runtime.concurrency());
    assertNotNull(runtime.recovery());
    assertEquals(0, runtime.recoveryContext().calls().size());
    assertFalse(data.toFile().exists());
  }
}
