package dynamitedb.integration.acceptance;

import dynamitedb.app.protocol.WireProtocol;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

final class AcceptanceClient implements AutoCloseable {
  private final Socket socket;
  private final DataInputStream input;
  private final DataOutputStream output;
  private volatile boolean closed;

  AcceptanceClient(int port) throws IOException {
    this(port, Duration.ofSeconds(30));
  }

  AcceptanceClient(int port, Duration timeout) throws IOException {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()
        || timeout.isZero()
        || timeout.toMillis() < 1
        || timeout.toMillis() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Socket timeout must be positive and fit in an int");
    }
    socket = new Socket();
    try {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
      socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
      socket.setTcpNoDelay(true);
      input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
      output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
      output.writeInt(WireProtocol.MAGIC);
      output.writeInt(WireProtocol.VERSION);
      output.flush();
      readStatus();
      input.readLong();
    } catch (IOException | RuntimeException failure) {
      try {
        socket.close();
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  synchronized QueryResult execute(String sql) throws IOException {
    if (closed) throw new IOException("Client is closed");
    if (sql == null || sql.getBytes(StandardCharsets.UTF_8).length > WireProtocol.MAX_SQL_BYTES) {
      throw new IOException("SQL exceeds the 1 MiB request limit");
    }
    output.writeByte(WireProtocol.EXECUTE);
    WireProtocol.writeString(output, sql);
    output.flush();
    readStatus();
    return WireProtocol.readResult(input);
  }

  void disconnectAbruptly() throws IOException {
    closed = true;
    socket.close();
  }

  private void readStatus() throws IOException {
    int status = input.readUnsignedByte();
    if (status == WireProtocol.ERROR) {
      ErrorCode code;
      try {
        code = ErrorCode.valueOf(WireProtocol.readString(input));
      } catch (IllegalArgumentException failure) {
        throw new IOException("Unknown server error code", failure);
      }
      throw new DbmsException(code, WireProtocol.readString(input));
    }
    if (status != WireProtocol.OK) throw new IOException("Invalid server response");
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      output.writeByte(WireProtocol.CLOSE);
      output.flush();
      readStatus();
    } finally {
      socket.close();
    }
  }
}
