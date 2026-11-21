package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.frm.CheckpointEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TableDdlAcceptanceTest extends PackagedServerAcceptanceSupport {
  @Test
  void tableDdlSurvivesCheckpointCrashRecoveryAndCascadeDrop() throws Exception {
    Path data = directory.resolve("table-ddl-data");
    try (PackagedServerProcess server = startServer(data, ProtocolType.STRICT_2PL, 1);
        AcceptanceClient client = connect(server)) {
      client.execute(
          "CREATE TABLE ddl_parent (id INTEGER PRIMARY KEY, ratio FLOAT,"
              + " code CHAR(4), label VARCHAR(32));");
      client.execute("INSERT INTO ddl_parent VALUES (1, 1.25, 'AB', 'first');");
      client.execute(
          "CREATE TABLE ddl_child (id INTEGER PRIMARY KEY, parent_id INTEGER,"
              + " FOREIGN KEY (parent_id) REFERENCES ddl_parent(id));");
      client.execute("INSERT INTO ddl_child VALUES (10, 1);");

      assertError(
          ErrorCode.SQL_BIND,
          () ->
              client.execute(
                  "CREATE TABLE ddl_parent (id INTEGER PRIMARY KEY, ratio FLOAT,"
                      + " code CHAR(4), label VARCHAR(32));"));
      assertError(
          ErrorCode.SQL_BIND,
          () -> client.execute("INSERT INTO ddl_parent VALUES (1, 2.0, 'CD', 'duplicate');"));
      assertError(
          ErrorCode.SQL_BIND, () -> client.execute("INSERT INTO ddl_child VALUES (11, 99);"));
      assertError(ErrorCode.INVALID_STATE, () -> client.execute("DROP TABLE ddl_parent;"));
      assertEquals(1, client.execute("SELECT id FROM ddl_parent;").rows().size());
      assertEquals(1, client.execute("SELECT id FROM ddl_child;").rows().size());

      long completedCheckpoints = checkpointCount(data, CheckpointEvent.END);
      awaitCondition(
          () -> checkpointCount(data, CheckpointEvent.END) > completedCheckpoints,
          Duration.ofSeconds(20),
          "checkpoint did not persist table DDL");
      server.killForcibly();
      client.disconnectAbruptly();
    }

    try (PackagedServerProcess recovered = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(recovered)) {
      assertEquals(1, onlyInt(client, "SELECT id FROM ddl_parent;"));
      assertEquals(
          new FloatValue(1.25),
          onlyRow(client, "SELECT ratio FROM ddl_parent;").values().getFirst());
      assertEquals(
          new TextValue("AB"), onlyRow(client, "SELECT code FROM ddl_parent;").values().getFirst());
      assertEquals(
          new TextValue("first"),
          onlyRow(client, "SELECT label FROM ddl_parent;").values().getFirst());
      assertEquals(1, client.execute("SELECT id FROM ddl_child;").rows().size());
      assertError(
          ErrorCode.SQL_BIND, () -> client.execute("INSERT INTO ddl_child VALUES (11, 99);"));
      assertError(ErrorCode.INVALID_STATE, () -> client.execute("DROP TABLE ddl_parent;"));

      client.execute("DROP TABLE ddl_parent CASCADE;");
      assertError(ErrorCode.SQL_BIND, () -> client.execute("SELECT id FROM ddl_parent;"));
      assertEquals(1, client.execute("SELECT id FROM ddl_child;").rows().size());
      client.execute("INSERT INTO ddl_child VALUES (11, 99);");
      assertEquals(2, client.execute("SELECT id FROM ddl_child;").rows().size());

      recovered.killForcibly();
      client.disconnectAbruptly();
    }

    try (PackagedServerProcess recoveredAgain = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(recoveredAgain)) {
      assertError(ErrorCode.SQL_BIND, () -> client.execute("SELECT id FROM ddl_parent;"));
      assertEquals(2, client.execute("SELECT id FROM ddl_child;").rows().size());
      client.execute("INSERT INTO ddl_child VALUES (12, 404);");
      assertEquals(3, client.execute("SELECT id FROM ddl_child;").rows().size());
    }
  }

  private static void assertError(ErrorCode expected, ThrowingRunnable action) {
    DbmsException failure = assertThrows(DbmsException.class, action::run);
    assertEquals(expected, failure.getCode());
  }

  private static long checkpointCount(Path data, CheckpointEvent event) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    DbmsException lastReadFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        return PersistentDataFiles.checkpointEventCount(data, event);
      } catch (DbmsException incompleteWalRead) {
        lastReadFailure = incompleteWalRead;
        Thread.sleep(10);
      }
    }
    throw new IOException("Could not read a complete checkpoint count", lastReadFailure);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
