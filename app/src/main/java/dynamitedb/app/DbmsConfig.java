package dynamitedb.app;

import java.net.InetSocketAddress;
import java.util.Objects;

public record DbmsConfig(String host, int port) {
  public DbmsConfig {
    Objects.requireNonNull(host, "host");
    if (host.isBlank() || port < 0 || port > 65535) {
      throw new IllegalArgumentException("Host and port must identify a valid TCP endpoint");
    }
  }

  public InetSocketAddress address() {
    return new InetSocketAddress(host, port);
  }
}
