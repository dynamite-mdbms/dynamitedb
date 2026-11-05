package dynamitedb.app;

import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import java.util.Objects;

public record DbmsConfig(int port, Path dataDirectory, ProtocolType protocol) {
  public DbmsConfig {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("Port must be between 0 and 65535");
    }
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(protocol, "protocol");
  }

  public static DbmsConfig defaults() {
    return new DbmsConfig(7777, Path.of("data"), ProtocolType.STRICT_2PL);
  }
}
