package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.client.RemoteQueryClient;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableDdlRuntimeTest {
  @TempDir Path directory;

  @Test
  void createInsertReadAndDdlSurviveRestart() throws Exception {
    DbmsConfig config = config("restart");
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      try (RemoteQueryClient client = connect(runtime, config)) {
        client.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name VARCHAR(32))");
        client.execute("INSERT INTO items VALUES (1, 'one')");
        assertEquals(1, client.execute("SELECT id FROM items").rows().size());
      }
    }

    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      try (RemoteQueryClient client = connect(runtime, config)) {
        assertEquals(1, client.execute("SELECT id FROM items").rows().size());
        assertEquals(
            ErrorCode.SQL_BIND,
            assertThrows(
                    DbmsException.class,
                    () -> client.execute("INSERT INTO items VALUES (1, 'duplicate')"))
                .getCode());
        client.execute("DROP TABLE items");
      }
    }
    try (PersistentRuntime runtime = new PersistentRuntime(config);
        RemoteQueryClient client = connect(runtime, config)) {
      assertEquals(
          ErrorCode.SQL_BIND,
          assertThrows(DbmsException.class, () -> client.execute("SELECT id FROM items"))
              .getCode());
    }
  }

  @Test
  void duplicateAndRestrictFailuresAreCatalogErrorsAndCascadeKeepsReferencingTable()
      throws Exception {
    DbmsConfig config = config("constraints");
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      try (RemoteQueryClient client = connect(runtime, config)) {
        client.execute("CREATE TABLE parents (id INTEGER PRIMARY KEY)");
        client.execute(
            "CREATE TABLE children (id INTEGER PRIMARY KEY, parent_id INTEGER,"
                + " FOREIGN KEY (parent_id) REFERENCES parents(id))");
        client.execute("INSERT INTO parents VALUES (7)");
        client.execute("INSERT INTO children VALUES (1, 7)");

        assertEquals(
            ErrorCode.SQL_BIND,
            assertThrows(
                    DbmsException.class,
                    () -> client.execute("CREATE TABLE parents (id INTEGER PRIMARY KEY)"))
                .getCode());
        assertEquals(
            ErrorCode.INVALID_STATE,
            assertThrows(DbmsException.class, () -> client.execute("DROP TABLE parents"))
                .getCode());
        assertEquals(1, client.execute("SELECT id FROM parents WHERE id = 7").rows().size());

        client.execute("DROP TABLE parents CASCADE");
        client.execute("INSERT INTO children VALUES (2, 99)");
        assertEquals(2, client.execute("SELECT id FROM children").rows().size());
      }
    }
  }

  @Test
  void ddlInsideExplicitTransactionIsRejectedWithoutEndingTransaction() throws Exception {
    DbmsConfig config = config("explicit");
    try (PersistentRuntime runtime = new PersistentRuntime(config)) {
      try (RemoteQueryClient client = connect(runtime, config)) {
        client.execute("BEGIN");
        var tx = client.execute("SELECT student_id FROM student");
        assertFalse(tx.rows().isEmpty());

        assertEquals(
            ErrorCode.INVALID_STATE,
            assertThrows(
                    DbmsException.class,
                    () -> client.execute("CREATE TABLE forbidden (id INTEGER)"))
                .getCode());
        assertEquals(
            ErrorCode.INVALID_STATE,
            assertThrows(DbmsException.class, () -> client.execute("DROP TABLE student"))
                .getCode());
        client.execute("COMMIT");
      }
    }
  }

  @Test
  void ddlWaitsForTransactionsBeforeMutatingCatalog() throws Exception {
    DbmsConfig config = config("wait");
    var runtime = new StubRuntime(config);
    try {
      runtime.bootstrap().start(config);
      var processor = runtime.processor();
      var reader = processor.openSession("reader");
      var ddl = processor.openSession("ddl");
      processor.execute(reader, "CREATE TABLE existing (id INTEGER)");
      processor.execute(reader, "BEGIN");
      var create =
          new FutureTask<>(() -> processor.execute(ddl, "CREATE TABLE waiting (id INTEGER)"));
      Thread worker = Thread.ofPlatform().daemon().unstarted(create);
      try {
        processor.execute(reader, "INSERT INTO existing VALUES (1)");
        processor.execute(reader, "SELECT id FROM existing");
        worker.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!create.isDone()
            && worker.getState() != Thread.State.TIMED_WAITING
            && System.nanoTime() < deadline) {
          Thread.sleep(5);
        }
        assertEquals(Thread.State.TIMED_WAITING, worker.getState(), "DDL must reach the CCM wait");
        assertFalse(runtime.catalog().listTables().contains("waiting"));
      } finally {
        processor.execute(reader, "COMMIT");
      }
      create.get(5, TimeUnit.SECONDS);
      assertTrue(runtime.catalog().listTables().contains("waiting"));
      processor.closeSession(ddl);
      processor.closeSession(reader);
    } finally {
      runtime.bootstrap().shutdown();
    }
  }

  @Test
  void stubRuntimeUsesTheSameDdlMaintenanceDispatch() throws Exception {
    DbmsConfig config = config("stub");
    StubRuntime runtime = new StubRuntime(config);
    try {
      runtime.bootstrap().start(config);
      var session = runtime.processor().openSession("stub-ddl");
      runtime.processor().execute(session, "CREATE TABLE items (id INTEGER PRIMARY KEY)");
      runtime.processor().execute(session, "INSERT INTO items VALUES (1)");
      assertEquals(1, runtime.processor().execute(session, "SELECT id FROM items").rows().size());
      runtime.processor().closeSession(session);
    } finally {
      runtime.bootstrap().shutdown();
    }
  }

  private RemoteQueryClient connect(PersistentRuntime runtime, DbmsConfig config) throws Exception {
    runtime.bootstrap().start(config);
    return new RemoteQueryClient("127.0.0.1", runtime.bootstrap().port());
  }

  private DbmsConfig config(String name) {
    DbmsConfig config = new DbmsConfig(0, directory.resolve(name), ProtocolType.STRICT_2PL);
    return config;
  }
}
