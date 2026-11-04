package dynamitedb.contracts.transaction;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import java.util.Locale;

public enum ProtocolType {
  STRICT_2PL,
  STRICT_TIMESTAMP;

  public static ProtocolType defaultProtocol() {
    return STRICT_2PL;
  }

  public static ProtocolType fromConfig(String value) {
    if (value == null || value.isBlank()) {
      return defaultProtocol();
    }
    return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
      case "2pl", "strict_2pl", "lock", "locking" -> STRICT_2PL;
      case "timestamp", "ts", "strict_timestamp" -> STRICT_TIMESTAMP;
      default ->
          throw new DbmsException(
              ErrorCode.UNSUPPORTED_OPERATION,
              "Protokol concurrency control tidak dikenal: "
                  + value
                  + ". Pilihan yang tersedia: 2pl atau timestamp");
    };
  }

  public String displayName() {
    return switch (this) {
      case STRICT_2PL -> "Strict Two Phase Locking";
      case STRICT_TIMESTAMP -> "Strict Timestamp Ordering";
    };
  }
}
