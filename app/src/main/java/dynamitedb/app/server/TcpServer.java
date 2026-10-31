package dynamitedb.app.server;

import dynamitedb.app.protocol.WireProtocol;
import dynamitedb.contracts.api.QueryProcessor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.SessionId;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Shared QP server: separate session and ordered execution queue for every socket. */
public final class TcpServer implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(TcpServer.class.getName());
  private final QueryProcessor processor;
  private final InetSocketAddress address;
  private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean running = new AtomicBoolean();
  private ServerSocket listener;
  private boolean started;

  public TcpServer(QueryProcessor processor, int port) {
    this(processor, new InetSocketAddress("127.0.0.1", port));
  }

  public TcpServer(QueryProcessor processor, InetSocketAddress address) {
    this.processor = java.util.Objects.requireNonNull(processor, "processor");
    this.address = java.util.Objects.requireNonNull(address, "address");
  }

  public synchronized void start() throws IOException {
    if (started) {
      throw new IllegalStateException("Server cannot be started twice");
    }
    started = true;
    listener = new ServerSocket();
    try {
      listener.bind(address);
      running.set(true);
      handlers.submit(this::acceptClients);
    } catch (IOException | RuntimeException failure) {
      running.set(false);
      listener.close();
      handlers.shutdownNow();
      throw failure;
    }
  }

  public synchronized int port() {
    if (!running.get()) {
      throw new IllegalStateException("Server is not running");
    }
    return listener.getLocalPort();
  }

  private void acceptClients() {
    while (running.get()) {
      try {
        Socket socket = listener.accept();
        if (!running.get()) {
          socket.close();
          break;
        }
        Connection connection = new Connection(socket);
        connections.add(connection);
        try {
          handlers.submit(connection);
        } catch (RuntimeException failure) {
          connection.cancel();
          connections.remove(connection);
          throw failure;
        }
      } catch (IOException | RuntimeException failure) {
        if (running.get()) {
          LOG.warning("Unable to accept client: " + failure.getMessage());
        }
      }
    }
  }

  private final class Connection implements Runnable {
    private final Socket socket;
    private final ExecutorService requests =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("qp-request-", 0).factory());
    private SessionId session;

    private Connection(Socket socket) {
      this.socket = socket;
    }

    @Override
    public void run() {
      try (socket) {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        DataInputStream input =
            new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream output =
            new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        if (input.readInt() != WireProtocol.MAGIC || input.readInt() != WireProtocol.VERSION) {
          throw new IOException("Unsupported protocol version");
        }
        session = processor.openSession(socket.getRemoteSocketAddress().toString());
        output.writeByte(WireProtocol.OK);
        output.writeLong(session.value());
        output.flush();
        socket.setSoTimeout(0);
        while (running.get()) {
          int command = input.readUnsignedByte();
          if (command == WireProtocol.CLOSE) {
            requests.shutdown();
            if (!requests.awaitTermination(5, TimeUnit.SECONDS)) {
              throw new IOException("Pending request did not finish");
            }
            // Confirm CLOSE only after transaction rollback/session cleanup succeeds.
            processor.closeSession(session);
            session = null;
            output.writeByte(WireProtocol.OK);
            output.flush();
            break;
          }
          if (command != WireProtocol.EXECUTE) {
            throw new IOException("Unknown request command");
          }
          String sql = WireProtocol.readString(input, WireProtocol.MAX_SQL_BYTES);
          requests.submit(() -> execute(sql, output));
          // Keep reading to notice EOF and interrupt an outstanding WAITING request.
        }
      } catch (EOFException ignored) {
        // Normal disconnect path, including a client killed during a transaction.
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (IOException | RuntimeException failure) {
        if (running.get()) {
          LOG.fine("Client disconnected: " + failure.getMessage());
        }
      } finally {
        cancel();
        if (session != null) {
          try {
            processor.closeSession(session);
          } catch (RuntimeException failure) {
            LOG.warning(
                "Session " + session.value() + " cleanup requires retry: " + failure.getMessage());
          }
        }
        connections.remove(this);
      }
    }

    private void execute(String sql, DataOutputStream output) {
      try {
        var result = processor.execute(session, sql);
        output.writeByte(WireProtocol.OK);
        WireProtocol.writeResult(output, result);
        output.flush();
      } catch (DbmsException failure) {
        sendError(output, failure.getCode(), failure.getMessage());
      } catch (RuntimeException failure) {
        sendError(output, ErrorCode.INVALID_STATE, "Query execution failed");
      } catch (IOException failure) {
        cancel();
      }
    }

    private void sendError(DataOutputStream output, ErrorCode code, String message) {
      try {
        output.writeByte(WireProtocol.ERROR);
        WireProtocol.writeString(output, code.name());
        WireProtocol.writeString(output, message == null ? "Query failed" : message);
        output.flush();
      } catch (IOException failure) {
        cancel();
      }
    }

    private void cancel() {
      requests.shutdownNow();
      try {
        socket.close();
      } catch (IOException ignored) {
        // Cleanup continues through the QP boundary.
      }
    }
  }

  public void stop() {
    synchronized (this) {
      started = true;
      if (running.getAndSet(false)) {
        try {
          listener.close();
        } catch (IOException ignored) {
          // Existing clients must still be stopped.
        }
        connections.forEach(Connection::cancel);
      }
    }
    handlers.shutdownNow();
    try {
      handlers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    stop();
  }
}
