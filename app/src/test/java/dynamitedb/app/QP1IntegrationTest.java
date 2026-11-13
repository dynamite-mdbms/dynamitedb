package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.cli.CliClient;
import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.app.protocol.WireProtocol;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.transaction.ProtocolType;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** QP1 client/server flows through the real QO, QP, SM, CCM and FRM composition. */
class QP1IntegrationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(ProtocolType.class)
  void clientTransactionsStaySeparateAndCommittedChangesSurviveRestart(ProtocolType protocol)
      throws Exception {
    DbmsConfig config = config(protocol);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient observer = client(runtime)) {
        try (RemoteQueryClient writer = client(runtime)) {
          assertNotEquals(writer.sessionId(), observer.sessionId());
          assertEquals(
              ErrorCode.INVALID_STATE,
              assertThrows(DbmsException.class, () -> observer.execute("COMMIT;")).getCode());

          writer.execute("BEGIN;");
          writer.execute("INSERT INTO student VALUES (9001, 'Aborted', 3.5, 'A');");
          assertEquals(1, writer.execute(select(9001)).rows().size());
          assertEquals("ABORT", writer.execute("ABORT;").message());
          assertTrue(observer.execute(select(9001)).rows().isEmpty());

          assertEquals(
              1,
              writer
                  .execute("INSERT INTO student VALUES (9002, 'Committed', 3.0, 'A');")
                  .affectedRows());
          assertEquals(1, observer.execute(select(9002)).rows().size());
        }
        assertEquals(1, observer.execute(select(9002)).rows().size());
      }
    }

    try (PersistentRuntime restarted = new PersistentRuntime(config)) {
      restarted.bootstrap().start(config);
      try (RemoteQueryClient client = client(restarted)) {
        assertTrue(client.execute(select(9001)).rows().isEmpty());
        assertEquals(1, client.execute(select(9002)).rows().size());
        assertEquals(
            1,
            client
                .execute("INSERT INTO student VALUES (9003, 'After restart', 4.0, 'A');")
                .affectedRows());
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ProtocolType.class)
  void abruptDisconnectRollsBackThroughRealRecoveryAndReleasesAccess(ProtocolType protocol)
      throws Exception {
    DbmsConfig config = config(protocol);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (SocketClient disconnected = new SocketClient(runtime.bootstrap().port())) {
        disconnected.execute("BEGIN;");
        disconnected.execute("INSERT INTO student VALUES (9010, 'Disconnected', 2.0, 'B');");
        assertEquals(1, disconnected.execute(select(9010)).rows().size());
      }

      try (SocketClient observer = new SocketClient(runtime.bootstrap().port())) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
          try {
            assertTrue(observer.execute(select(9010)).rows().isEmpty());
            break;
          } catch (DbmsException failure) {
            // A timestamp reader can be denied while disconnect cleanup is still in flight.
            assertEquals(ErrorCode.ACCESS_ABORTED, failure.getCode());
            assertTrue(System.nanoTime() < deadline, "Disconnected transaction was not cleaned up");
            Thread.sleep(10);
          }
        }
        assertEquals(
            1,
            observer
                .execute("INSERT INTO student VALUES (9011, 'Still connected', 3.0, 'A');")
                .affectedRows());
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ProtocolType.class)
  void realCliDisplaysQualifiedJoinHeadersAndContinuesAfterQueryErrors(ProtocolType protocol)
      throws Exception {
    DbmsConfig config = config(protocol);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (RemoteQueryClient client = client(runtime)) {
        assertEquals(
            List.of(new Row(List.of(new IntValue(2))), new Row(List.of(new IntValue(1)))),
            client
                .execute(
                    "SELECT student_id FROM student WHERE student_id < 3 ORDER BY student_id DESC;")
                .rows());
      }

      StringWriter output = new StringWriter();
      new CliClient()
          .run(
              "127.0.0.1",
              runtime.bootstrap().port(),
              new StringReader(
                  "SELECT a.student_id, b.student_id\n"
                      + "FROM student AS a JOIN student AS b ON a.student_id = b.student_id\n"
                      + "WHERE a.student_id = 1;\n"
                      + "nonsense;\n"
                      + "SELECT student_id FROM student WHERE student_id = 99999;\n"
                      + "exit;\n"),
              new PrintWriter(output),
              false);
      String text = output.toString();
      assertTrue(text.contains("a.student_id"), text);
      assertTrue(text.contains("b.student_id"), text);
      assertTrue(text.contains("1 row(s)"), text);
      assertTrue(text.contains("ERROR [SQL_SYNTAX]"), text);
      assertTrue(text.contains("0 row(s)"), text);
    }
  }

  @Test
  void waitingClientResumesAfterAnotherClientCommits() throws Exception {
    DbmsConfig config = config(ProtocolType.STRICT_2PL);
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      runtime.bootstrap().start(config);
      try (SocketClient writer = new SocketClient(runtime.bootstrap().port());
          SocketClient reader = new SocketClient(runtime.bootstrap().port())) {
        writer.execute("BEGIN;");
        writer.execute("INSERT INTO student VALUES (9020, 'Waited', 3.5, 'A');");
        reader.send(select(9020));
        reader.socket.setSoTimeout(300);
        assertThrows(SocketTimeoutException.class, reader::receive);

        assertEquals("COMMIT", writer.execute("COMMIT;").message());
        reader.socket.setSoTimeout(5000);
        assertEquals(1, reader.receive().rows().size());
        assertEquals(1, reader.execute(select(9020)).rows().size());
      }
    }
  }

  private DbmsConfig config(ProtocolType protocol) {
    return new DbmsConfig(0, directory.resolve("db"), protocol);
  }

  private static RemoteQueryClient client(PersistentRuntime runtime) throws IOException {
    return new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port());
  }

  private static String select(int id) {
    return "SELECT student_id FROM student WHERE student_id = " + id + ";";
  }

  /** Bounded socket reads and abrupt close exercise server cleanup without a CLOSE handshake. */
  private static final class SocketClient implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    private SocketClient(int port) throws IOException {
      socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(5000);
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

    private QueryResult execute(String sql) throws IOException {
      send(sql);
      return receive();
    }

    private QueryResult receive() throws IOException {
      int status = input.readUnsignedByte();
      if (status == WireProtocol.ERROR) {
        throw new DbmsException(
            ErrorCode.valueOf(WireProtocol.readString(input)), WireProtocol.readString(input));
      }
      assertEquals(WireProtocol.OK, status);
      return WireProtocol.readResult(input);
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
