package dynamitedb.app;

import dynamitedb.app.server.TcpServer;
import dynamitedb.contracts.api.QueryProcessor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

/** Admission gate. The composition root supplies FRM recovery and CCM initialization together. */
public final class DbmsBootstrap {
  private final QueryProcessor processor;
  private final Runnable recoverAndInitialize;
  private TcpServer server;

  public DbmsBootstrap(QueryProcessor processor, Runnable recoverAndInitialize) {
    this.processor = Objects.requireNonNull(processor, "processor");
    this.recoverAndInitialize =
        Objects.requireNonNull(recoverAndInitialize, "recoverAndInitialize");
  }

  public void start(DbmsConfig config) throws IOException {
    start(config, "127.0.0.1");
  }

  public void start(DbmsConfig config, String host) throws IOException {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(host, "host");
    if (server != null) throw new IllegalStateException("Server already started");
    // Callback must throw on failed recovery; no socket exists before it succeeds.
    recoverAndInitialize.run();
    TcpServer candidate = new TcpServer(processor, new InetSocketAddress(host, config.port()));
    candidate.start();
    server = candidate;
  }

  public void shutdown() {
    if (server != null) server.stop();
  }

  public int port() {
    if (server == null) throw new IllegalStateException("Server is not started");
    return server.port();
  }
}
