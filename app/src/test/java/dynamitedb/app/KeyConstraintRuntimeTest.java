package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateTablePlan;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KeyConstraintRuntimeTest {
  @TempDir Path directory;
  private StubRuntime runtime;
  private SessionId session;

  @AfterEach
  void stop() {
    if (runtime != null) {
      runtime.bootstrap().shutdown();
    }
  }

  @Test
  void rejectsDuplicateAndNullPrimaryKeysOnInsert() throws Exception {
    start(ProtocolType.STRICT_2PL);
    run("INSERT INTO parent VALUES (10, 1);");
    rejects("INSERT INTO parent VALUES (10, 1);");
    rejects("INSERT INTO parent VALUES (10, NULL);");
    assertEquals(1, run("SELECT * FROM parent;").rows().size());
  }

  @Test
  void validatesOrderedForeignKeysAndAllowsPartialNulls() throws Exception {
    start(ProtocolType.STRICT_2PL);
    run("INSERT INTO parent VALUES (10, 1);");
    rejects("INSERT INTO child VALUES (1, 10, 1);");
    run("INSERT INTO child VALUES (1, NULL, 99);");
    run("INSERT INTO child VALUES (2, 1, 10);");
    rejects("UPDATE child SET pb = 99;");
    assertEquals(
        List.of(new Row(List.of(new IntValue(2)))),
        run("SELECT id FROM child WHERE pa = 1 AND pb = 10;").rows());
  }

  @Test
  void rejectsMultiRowUpdateWithDuplicateTargetsOrUnaffectedKey() throws Exception {
    start(ProtocolType.STRICT_2PL);
    seedParents();
    rejects("UPDATE parent SET a = 4 WHERE a < 3;");
    rejects("UPDATE parent SET a = 3 WHERE a < 3;");
    rejects("UPDATE parent SET a = a + NULL WHERE a = 1;");
    assertParentsUnchanged();
  }

  @Test
  void restrictsParentChangesBeforeAnyRowsAreWritten() throws Exception {
    start(ProtocolType.STRICT_2PL);
    seedParents();
    run("INSERT INTO child VALUES (1, 2, 10);");
    assertEquals(1, run("UPDATE parent SET a = a WHERE a = 2;").affectedRows());
    rejects("UPDATE parent SET a = a + 10 WHERE a < 3;");
    rejects("DELETE FROM parent WHERE a < 3;");
    assertParentsUnchanged();
    assertEquals(1, run("DELETE FROM parent WHERE a = 1;").affectedRows());
  }

  @Test
  void multiAssignmentChecksReferencedPrimaryColumnsRegardlessOfAssignmentOrder() throws Exception {
    start(ProtocolType.STRICT_2PL);
    seedParents();
    run("INSERT INTO child VALUES (1, 1, 10);");

    rejects("UPDATE parent SET b = b, a = 4 WHERE a = 1;");

    assertParentsUnchanged();
    assertEquals(
        List.of(new Row(List.of(new IntValue(1), new IntValue(1), new IntValue(10)))),
        run("SELECT id, pa, pb FROM child;").rows());
  }

  @Test
  void selfReferenceUsesSurvivingRows() throws Exception {
    start(ProtocolType.STRICT_2PL);
    create(
        "CREATE TABLE node (id INTEGER PRIMARY KEY, parent_id INTEGER,"
            + " FOREIGN KEY (parent_id) REFERENCES node (id));");
    run("INSERT INTO node VALUES (1, 1);");
    run("INSERT INTO node VALUES (2, 1);");
    rejects("DELETE FROM node WHERE id = 1;");
    assertEquals(2, run("DELETE FROM node;").affectedRows());
  }

  @Test
  void textKeysUseSqlEqualityAndMustSurviveUtf8Storage() throws Exception {
    start(ProtocolType.STRICT_2PL);
    create("CREATE TABLE text_parent (id CHAR(12) PRIMARY KEY);");
    create(
        "CREATE TABLE text_child (id VARCHAR(12) PRIMARY KEY,"
            + " parent_id CHAR(12), FOREIGN KEY (parent_id) REFERENCES text_parent (id));");
    run("INSERT INTO text_parent VALUES ('hello');");
    rejects("INSERT INTO text_parent VALUES ('hello  ');");
    run("INSERT INTO text_child VALUES ('child', 'hello  ');");
    rejects("INSERT INTO text_child VALUES ('child  ', 'hello');");
    rejects("INSERT INTO text_parent VALUES ('\uD800');");
    rejects("DELETE FROM text_parent;");
  }

  @Test
  void floatKeysUseSqlSignedZeroEqualityAfterStorage() throws Exception {
    start(ProtocolType.STRICT_2PL);
    create("CREATE TABLE float_parent (id FLOAT PRIMARY KEY);");
    create("CREATE TABLE float_child (id FLOAT, FOREIGN KEY (id) REFERENCES float_parent (id));");
    run("INSERT INTO float_parent VALUES (0.0);");
    rejects("INSERT INTO float_parent VALUES (0);");
    run("INSERT INTO float_child VALUES (0.0);");
    // The parser canonicalizes literal -0.0, but arithmetic can produce it.
    rejects("UPDATE float_child SET id = id * -1.0;");
    run("INSERT INTO float_parent VALUES (2.0);");
    run("UPDATE float_parent SET id = 0.0 * -1.0 WHERE id = 2.0;");
    run("UPDATE float_child SET id = id * -1.0;");
    assertEquals(1, run("DELETE FROM float_parent WHERE id = 0.0;").affectedRows());
    rejects("DELETE FROM float_parent;");
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("parentChanges")
  void parentChangeWaitsForConcurrentChildInsertThenRejects(ProtocolType protocol, String sql)
      throws Exception {
    start(protocol);
    run("INSERT INTO parent VALUES (10, 1);");
    run("BEGIN;");
    run("INSERT INTO child VALUES (1, 1, 10);");
    long before = changesLogged();
    SessionId otherSession = runtime.processor().openSession("concurrent-parent-change");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(1);
      var change =
          executor.submit(
              () -> {
                started.countDown();
                return assertThrows(
                        DbmsException.class, () -> runtime.processor().execute(otherSession, sql))
                    .getCode();
              });
      try {
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> change.get(150, TimeUnit.MILLISECONDS));
      } finally {
        run("COMMIT;");
      }
      assertEquals(ErrorCode.SQL_BIND, change.get(5, TimeUnit.SECONDS));
    } finally {
      runtime.processor().closeSession(otherSession);
    }
    assertEquals(before, changesLogged(), sql + " must fail before the first WAL change");
    assertEquals(
        List.of(new Row(List.of(new IntValue(10), new IntValue(1)))),
        run("SELECT b, a FROM parent;").rows());
    assertEquals(
        List.of(new Row(List.of(new IntValue(1), new IntValue(1), new IntValue(10)))),
        run("SELECT id, pa, pb FROM child;").rows());
  }

  private static Stream<Arguments> parentChanges() {
    return Stream.of(ProtocolType.values())
        .flatMap(
            protocol ->
                Stream.of(
                    Arguments.of(protocol, "DELETE FROM parent;"),
                    Arguments.of(protocol, "UPDATE parent SET a = 2 WHERE a = 1;")));
  }

  private void start(ProtocolType protocol) throws Exception {
    var config = new DbmsConfig(0, directory.resolve("data"), protocol);
    runtime = new StubRuntime(config);
    runtime.bootstrap().start(config);
    session = runtime.processor().openSession("key-constraints");
    create("CREATE TABLE parent (b INTEGER, a INTEGER, PRIMARY KEY (a, b));");
    create(
        "CREATE TABLE child (id INTEGER PRIMARY KEY, pa INTEGER, pb INTEGER,"
            + " FOREIGN KEY (pa, pb) REFERENCES parent (a, b));");
    assertEquals(
        List.of(new ForeignKey("child", List.of("pa", "pb"), "parent", List.of("a", "b"))),
        runtime.catalog().listForeignKeys("child"));
  }

  // Install through SM so these tests can run independently of the SQL DDL executor.
  private void create(String ddl) {
    var plan = (CreateTablePlan) runtime.optimizer().prepare(ddl).root();
    runtime.storage().createTable(plan.definition());
  }

  private void seedParents() {
    run("INSERT INTO parent VALUES (10, 1);");
    run("INSERT INTO parent VALUES (10, 2);");
    run("INSERT INTO parent VALUES (10, 3);");
  }

  private void assertParentsUnchanged() {
    assertEquals(
        List.of(
            new Row(List.of(new IntValue(1))),
            new Row(List.of(new IntValue(2))),
            new Row(List.of(new IntValue(3)))),
        run("SELECT a FROM parent ORDER BY a;").rows());
  }

  private QueryResult run(String sql) {
    return runtime.processor().execute(session, sql);
  }

  private void rejects(String sql) {
    long before = changesLogged();
    assertEquals(
        ErrorCode.SQL_BIND, assertThrows(DbmsException.class, () -> run(sql), sql).getCode());
    assertEquals(before, changesLogged(), sql + " must fail before the first WAL change");
  }

  private long changesLogged() {
    return runtime.recoveryContext().calls().stream()
        .filter("WalService.appendChange"::equals)
        .count();
  }
}
