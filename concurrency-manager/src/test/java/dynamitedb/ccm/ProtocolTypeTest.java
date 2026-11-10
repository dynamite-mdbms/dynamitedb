package dynamitedb.ccm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.ProtocolType;
import org.junit.jupiter.api.Test;

class ProtocolTypeTest {

  @Test
  void defaultProtocolIsStrictTwoPhaseLocking() {
    assertEquals(ProtocolType.STRICT_2PL, ProtocolType.defaultProtocol());
  }

  @Test
  void blankConfigurationFallsBackToDefault() {
    assertEquals(ProtocolType.STRICT_2PL, ProtocolType.fromConfig(null));
    assertEquals(ProtocolType.STRICT_2PL, ProtocolType.fromConfig(" "));
  }

  @Test
  void configurationValuesAreCaseAndSeparatorInsensitive() {
    assertEquals(ProtocolType.STRICT_2PL, ProtocolType.fromConfig("2PL"));
    assertEquals(ProtocolType.STRICT_2PL, ProtocolType.fromConfig("strict-2pl"));
    assertEquals(ProtocolType.STRICT_TIMESTAMP, ProtocolType.fromConfig("timestamp"));
    assertEquals(ProtocolType.STRICT_TIMESTAMP, ProtocolType.fromConfig("STRICT_TIMESTAMP"));
  }

  @Test
  void unknownConfigurationValueIsRejected() {
    var error = assertThrows(DbmsException.class, () -> ProtocolType.fromConfig("optimistic"));

    assertEquals(ErrorCode.UNSUPPORTED_OPERATION, error.getCode());
  }

  @Test
  void everyProtocolHasADisplayName() {
    assertEquals("Strict Two Phase Locking", ProtocolType.STRICT_2PL.displayName());
    assertEquals("Strict Timestamp Ordering", ProtocolType.STRICT_TIMESTAMP.displayName());
  }
}
