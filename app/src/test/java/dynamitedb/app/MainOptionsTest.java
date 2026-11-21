package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.transaction.ProtocolType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MainOptionsTest {
  @Test
  void selectsBothSupportedServerProtocols() {
    assertEquals(ProtocolType.STRICT_2PL, Main.protocol("strict-2pl"));
    assertEquals(ProtocolType.STRICT_TIMESTAMP, Main.protocol("strict-timestamp"));
    assertThrows(IllegalArgumentException.class, () -> Main.protocol("timestamp"));
  }

  @Test
  void requiresARepresentablePositiveCheckpointInterval() {
    assertEquals(Duration.ofSeconds(60), Main.checkpointInterval("60"));
    assertThrows(IllegalArgumentException.class, () -> Main.checkpointInterval("0"));
    assertThrows(IllegalArgumentException.class, () -> Main.checkpointInterval("-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Main.checkpointInterval(Long.toString(Long.MAX_VALUE)));
  }
}
