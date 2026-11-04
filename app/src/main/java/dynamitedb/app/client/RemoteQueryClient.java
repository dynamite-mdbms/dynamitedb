package dynamitedb.app.client;

import dynamitedb.app.protocol.WireProtocol;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One socket owns one server-side session. Requests on a connection are ordered. */
public final class RemoteQueryClient implements AutoCloseable {
  private final Socket socket;
  private final DataInputStream input;
  private final DataOutputStream output;
  private final SessionId sessionId;
  private boolean closed;

  public RemoteQueryClient(String host, int port) throws IOException {
    socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), 5_000);
      socket.setTcpNoDelay(true);
      input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
      output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
      output.writeInt(WireProtocol.MAGIC);
      output.writeInt(WireProtocol.VERSION);
      output.flush();
      readStatus();
      sessionId = new SessionId(input.readLong());
    } catch (IOException | RuntimeException failure) {
      try {
        socket.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public SessionId sessionId() {
    return sessionId;
  }

  public synchronized QueryResult execute(String sql) throws IOException {
    if (closed) {
      throw new IOException("Client is closed");
    }
    if (sql == null || sql.getBytes(StandardCharsets.UTF_8).length > WireProtocol.MAX_SQL_BYTES) {
      throw new IOException("SQL exceeds the 1 MiB request limit");
    }
    output.writeByte(WireProtocol.EXECUTE);
    WireProtocol.writeString(output, sql);
    output.flush();
    readStatus();
    return WireProtocol.readResult(input);
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
    if (status != WireProtocol.OK) {
      throw new IOException("Invalid server response");
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed) {
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
}
