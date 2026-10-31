package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DbmsConfigTest {
  @Test
  void exposesCanonicalComponentsAndDefaults() {
    DbmsConfig config = DbmsConfig.defaults();

    assertEquals(7777, config.port());
    assertEquals(Path.of("data"), config.dataDirectory());
    assertEquals(ProtocolType.STRICT_2PL, config.protocol());
  }

  @Test
  void rejectsInvalidPort() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DbmsConfig(65536, Path.of("data"), ProtocolType.STRICT_2PL));
  }
}
