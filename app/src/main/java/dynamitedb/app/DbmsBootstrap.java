package dynamitedb.app;

import dynamitedb.app.server.TcpServer;
import dynamitedb.contracts.api.QueryProcessor;
import java.io.IOException;
import java.util.Objects;

/** Admission gate. The composition root supplies FRM recovery and CCM initialization together. */
public final class DbmsBootstrap {
  private final QueryProcessor processor;
  private final Runnable recoverAndInitialize;

  public DbmsBootstrap(QueryProcessor processor, Runnable recoverAndInitialize) {
    this.processor = Objects.requireNonNull(processor, "processor");
    this.recoverAndInitialize =
        Objects.requireNonNull(recoverAndInitialize, "recoverAndInitialize");
  }

  public TcpServer start(DbmsConfig config) throws IOException {
    // Callback must throw on failed recovery; no socket exists before it succeeds.
    recoverAndInitialize.run();
    TcpServer server = new TcpServer(processor, config.address());
    server.start();
    return server;
  }
}
