package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.app.mock.MockRuntime;
import dynamitedb.app.protocol.WireProtocol;
import dynamitedb.app.server.TcpServer;
import dynamitedb.contracts.api.QueryProcessor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClientServerTest {
  @Test
  void clientsKeepSeparateTransactionsAndObserveCommittedResults() throws Exception {
    try (TcpServer server = start(new MockRuntime().processor());
        RemoteQueryClient second = new RemoteQueryClient("127.0.0.1", server.port())) {
      try (RemoteQueryClient first = new RemoteQueryClient("127.0.0.1", server.port())) {
        assertFalse(first.sessionId().equals(second.sessionId()));
        first.execute("BEGIN;");
        first.execute("INSERT INTO demo VALUES (4, 'Dina');");
        assertEquals(1, first.execute("SELECT * FROM demo WHERE id = 4;").rows().size());
        assertEquals(0, second.execute("SELECT * FROM demo WHERE id = 4;").rows().size());
        assertThrows(DbmsException.class, () -> second.execute("COMMIT;"));
        first.execute("COMMIT;");
        assertEquals(1, second.execute("SELECT * FROM demo WHERE id = 4;").rows().size());
      }
      assertEquals(4, second.execute("SELECT * FROM demo;").rows().size());
    }
  }

  @Test
  void errorsDoNotDisconnectTheClient() throws Exception {
    try (TcpServer server = start(new MockRuntime().processor());
        RemoteQueryClient client = new RemoteQueryClient("127.0.0.1", server.port())) {
      assertEquals(
          ErrorCode.SQL_SYNTAX,
          assertThrows(DbmsException.class, () -> client.execute("nonsense;")).getCode());
      assertEquals(
          ErrorCode.STORAGE_FAILURE,
          assertThrows(DbmsException.class, () -> client.execute("SELECT * FROM fail;")).getCode());
      assertEquals(3, client.execute("SELECT * FROM demo;").rows().size());
    }
  }

  @Test
  void disconnectAbortsUncommittedChanges() throws Exception {
    CountDownLatch closed = new CountDownLatch(1);
    QueryProcessor processor =
        new ForwardingProcessor(new MockRuntime().processor()) {
          @Override
          public void closeSession(SessionId session) {
            super.closeSession(session);
            closed.countDown();
          }
        };
    try (TcpServer server = start(processor)) {
      try (RawClient client = new RawClient(server.port())) {
        client.query("BEGIN;");
        client.query("INSERT INTO demo VALUES (4, 'Dina');");
      }
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      try (RemoteQueryClient client = new RemoteQueryClient("127.0.0.1", server.port())) {
        assertEquals(0, client.execute("SELECT * FROM demo WHERE id = 4;").rows().size());
      }
    }
  }

  @Test
  void failedDisconnectCleanupIsRetriedWithoutBlockingOtherClients() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch closed = new CountDownLatch(1);
    QueryProcessor processor =
        new ForwardingProcessor(new MockRuntime().processor()) {
          @Override
          public void closeSession(SessionId session) {
            if (attempts.incrementAndGet() <= 2)
              throw new DbmsException(ErrorCode.WAL_FAILURE, "temporary rollback failure");
            super.closeSession(session);
            closed.countDown();
          }
        };
    try (TcpServer server = start(processor)) {
      try (RawClient client = new RawClient(server.port())) {
        client.query("BEGIN;");
        client.query("INSERT INTO demo VALUES (4, 'Dina');");
      }
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertTrue(attempts.get() >= 3);
      try (RemoteQueryClient other = new RemoteQueryClient("127.0.0.1", server.port())) {
        assertEquals(0, other.execute("SELECT * FROM demo WHERE id = 4;").rows().size());
      }
    }
  }

  @Test
  void blockedRequestAllowsOtherClientsAndIsInterruptedOnDisconnect() throws Exception {
    AtomicReference<SessionId> first = new AtomicReference<>();
    CountDownLatch waiting = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    CountDownLatch closed = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    AtomicInteger firstClientExecutions = new AtomicInteger();
    QueryProcessor processor =
        new ForwardingProcessor(new MockRuntime().processor()) {
          @Override
          public SessionId openSession(String client) {
            SessionId session = super.openSession(client);
            first.compareAndSet(null, session);
            return session;
          }

          @Override
          public QueryResult execute(SessionId session, String sql) {
            if (session.equals(first.get()) && sql.startsWith("SELECT")) {
              firstClientExecutions.incrementAndGet();
              waiting.countDown();
              try {
                released.await();
              } catch (InterruptedException cancelled) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new DbmsException(ErrorCode.ACCESS_ABORTED, "access wait cancelled");
              }
            }
            return super.execute(session, sql);
          }

          @Override
          public void closeSession(SessionId session) {
            super.closeSession(session);
            if (session.equals(first.get())) closed.countDown();
          }
        };
    try (TcpServer server = start(processor)) {
      try (RawClient client = new RawClient(server.port())) {
        client.query("BEGIN;");
        client.send("SELECT * FROM demo;");
        assertTrue(waiting.await(3, TimeUnit.SECONDS));
        try (RemoteQueryClient other = new RemoteQueryClient("127.0.0.1", server.port())) {
          assertEquals(3, other.execute("SELECT * FROM demo;").rows().size());
        }
        for (int i = 0; i < 10; i++) client.send("SELECT * FROM demo;");
      }
      assertTrue(closed.await(3, TimeUnit.SECONDS));
      assertEquals(1, firstClientExecutions.get());
      // The cleanup and request tasks finish independently after the socket is closed.
      assertTimeoutPreemptively(
          Duration.ofSeconds(3),
          () -> {
            while (!interrupted.get()) Thread.sleep(10);
          });
    } finally {
      released.countDown();
    }
  }

  @Test
  void pipelinedRequestsHaveBoundedWorkAndOrderedOverloadResponse() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    QueryProcessor processor =
        new ForwardingProcessor(new MockRuntime().processor()) {
          @Override
          public QueryResult execute(SessionId session, String sql) {
            if (executions.incrementAndGet() == 1) {
              blocked.countDown();
              try {
                released.await();
              } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new DbmsException(ErrorCode.ACCESS_ABORTED, "request cancelled");
              }
            }
            return super.execute(session, sql);
          }
        };

    try (TcpServer server = start(processor);
        RawClient client = new RawClient(server.port())) {
      client.send("SELECT * FROM demo;");
      assertTrue(blocked.await(3, TimeUnit.SECONDS));
      for (int i = 1; i < 100; i++) client.send("SELECT * FROM demo;");
      released.countDown();

      for (int i = 0; i < 16; i++) {
        assertEquals(WireProtocol.OK, client.input.readUnsignedByte());
        assertEquals(3, WireProtocol.readResult(client.input).rows().size());
      }
      assertEquals(WireProtocol.ERROR, client.input.readUnsignedByte());
      assertEquals(ErrorCode.INVALID_STATE.name(), WireProtocol.readString(client.input));
      assertEquals("Too many pending requests", WireProtocol.readString(client.input));
      assertThrows(EOFException.class, client.input::readUnsignedByte);
      assertEquals(16, executions.get());
    } finally {
      released.countDown();
    }
  }

  @Test
  void shutdownStopsCleanupRetriesWhenRollbackKeepsFailing() throws Exception {
    CountDownLatch attempted = new CountDownLatch(1);
    QueryProcessor processor =
        new ForwardingProcessor(new MockRuntime().processor()) {
          @Override
          public void closeSession(SessionId session) {
            attempted.countDown();
            throw new DbmsException(ErrorCode.WAL_FAILURE, "rollback unavailable");
          }
        };
    try (TcpServer server = start(processor)) {
      try (RawClient client = new RawClient(server.port())) {
        client.query("BEGIN;");
      }
      assertTrue(attempted.await(3, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(3), server::stop);
    }
  }

  private static TcpServer start(QueryProcessor processor) throws IOException {
    TcpServer server = new TcpServer(processor, 0);
    server.start();
    return server;
  }

  private static class ForwardingProcessor implements QueryProcessor {
    private final QueryProcessor delegate;

    private ForwardingProcessor(QueryProcessor delegate) {
      this.delegate = delegate;
    }

    @Override
    public SessionId openSession(String client) {
      return delegate.openSession(client);
    }

    @Override
    public QueryResult execute(SessionId session, String sql) {
      return delegate.execute(session, sql);
    }

    @Override
    public void closeSession(SessionId session) {
      delegate.closeSession(session);
    }
  }

  /** Socket close deliberately omits the protocol CLOSE request to simulate a lost client. */
  private static final class RawClient implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    private RawClient(int port) throws IOException {
      socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(3000);
      input = new DataInputStream(socket.getInputStream());
      output = new DataOutputStream(socket.getOutputStream());
      output.writeInt(WireProtocol.MAGIC);
      output.writeInt(WireProtocol.VERSION);
      output.flush();
      assertEquals(WireProtocol.OK, input.readUnsignedByte());
      input.readLong();
    }

    private void send(String sql) throws IOException {
      output.writeByte(WireProtocol.EXECUTE);
      WireProtocol.writeString(output, sql);
      output.flush();
    }

    private void query(String sql) throws IOException {
      send(sql);
      assertEquals(WireProtocol.OK, input.readUnsignedByte());
      WireProtocol.readResult(input);
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
