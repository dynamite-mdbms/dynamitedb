package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbmsBootstrapRetryTest {
  @Test
  void bootstrapCanRetryAfterBindFailure(@TempDir Path directory) throws Exception {
    Path data = directory.resolve("unwritten-data");
    int port;
    DbmsConfig config;
    StubRuntime runtime;
    try (ServerSocket occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress("127.0.0.1", 0));
      port = occupied.getLocalPort();
      DbmsConfig defaults = DbmsConfig.defaults();
      config = new DbmsConfig(port, data, defaults.protocol());
      runtime = new StubRuntime(config);

      assertFalse(Files.exists(data));
      assertThrows(IOException.class, () -> runtime.bootstrap().start(config));
    }

    runtime.bootstrap().start(config);
    assertEquals(port, runtime.bootstrap().port());
    runtime.bootstrap().shutdown();

    try (ServerSocket released = new ServerSocket()) {
      released.bind(new InetSocketAddress("127.0.0.1", port));
    }
    assertFalse(Files.exists(data));
  }
}
