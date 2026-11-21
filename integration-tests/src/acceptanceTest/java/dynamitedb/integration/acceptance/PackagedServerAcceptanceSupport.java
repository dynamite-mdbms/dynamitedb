package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.ProtocolType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;

abstract class PackagedServerAcceptanceSupport {
  protected static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);

  @TempDir protected Path directory;

  protected PackagedServerProcess startServer(ProtocolType protocol) throws IOException {
    return PackagedServerProcess.start(directory.resolve("data"), argument(protocol));
  }

  protected PackagedServerProcess startServer(Path dataDirectory, ProtocolType protocol)
      throws IOException {
    return PackagedServerProcess.start(dataDirectory, argument(protocol));
  }

  protected PackagedServerProcess startServer(
      Path dataDirectory, ProtocolType protocol, long checkpointSeconds) throws IOException {
    return PackagedServerProcess.start(dataDirectory, argument(protocol), checkpointSeconds);
  }

  protected static AcceptanceClient connect(PackagedServerProcess server)
      throws IOException, InterruptedException {
    return new AcceptanceClient(server.port());
  }

  protected static void assertAffected(AcceptanceClient client, String sql, long expected)
      throws IOException {
    assertEquals(expected, client.execute(sql).affectedRows(), sql);
  }

  protected static Row onlyRow(AcceptanceClient client, String sql) throws IOException {
    List<Row> rows = client.execute(sql).rows();
    assertEquals(1, rows.size(), sql);
    return rows.getFirst();
  }

  protected static String onlyText(AcceptanceClient client, String sql) throws IOException {
    return ((TextValue) onlyRow(client, sql).values().getFirst()).value();
  }

  protected static int onlyInt(AcceptanceClient client, String sql) throws IOException {
    return ((IntValue) onlyRow(client, sql).values().getFirst()).value();
  }

  protected static void assertNoRows(AcceptanceClient client, String sql) throws IOException {
    assertTrue(client.execute(sql).rows().isEmpty(), sql);
  }

  protected static String argument(ProtocolType protocol) {
    return switch (protocol) {
      case STRICT_2PL -> "strict-2pl";
      case STRICT_TIMESTAMP -> "strict-timestamp";
    };
  }

  protected static <T> T await(java.util.concurrent.Future<T> future, Duration timeout)
      throws Exception {
    return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  protected static void awaitCondition(
      CheckedBooleanSupplier condition, Duration timeout, String failureMessage) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.get()) return;
      Thread.sleep(25);
    }
    fail(failureMessage);
  }

  @FunctionalInterface
  protected interface CheckedBooleanSupplier {
    boolean get() throws Exception;
  }
}
