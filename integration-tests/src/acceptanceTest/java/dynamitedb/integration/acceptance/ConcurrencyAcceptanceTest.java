package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.transaction.ProtocolType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConcurrencyAcceptanceTest extends PackagedServerAcceptanceSupport {
  @ParameterizedTest
  @EnumSource(ProtocolType.class)
  void disconnectRollsBackTheSessionsUncommittedChanges(ProtocolType protocol) throws Exception {
    try (PackagedServerProcess server = startServer(protocol)) {
      try (AcceptanceClient setup = connect(server)) {
        setup.execute("CREATE TABLE disconnect_rows (id INTEGER PRIMARY KEY, value VARCHAR(32));");
        setup.execute("INSERT INTO disconnect_rows VALUES (1, 'before');");
      }

      AcceptanceClient disconnected = connect(server);
      disconnected.execute("BEGIN;");
      disconnected.execute("UPDATE disconnect_rows SET value = 'uncommitted' WHERE id = 1;");
      disconnected.execute("INSERT INTO disconnect_rows VALUES (2, 'uncommitted');");
      disconnected.disconnectAbruptly();

      try (AcceptanceClient observer = connect(server)) {
        assertEquals(
            "before", onlyText(observer, "SELECT value FROM disconnect_rows WHERE id = 1;"));
        assertNoRows(observer, "SELECT id FROM disconnect_rows WHERE id = 2;");
      }
    }
  }

  @Test
  void strict2plReadersAndWritersWaitForConflictingTransactions() throws Exception {
    try (PackagedServerProcess server = startServer(ProtocolType.STRICT_2PL);
        AcceptanceClient owner = connect(server);
        AcceptanceClient waiting = connect(server);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      owner.execute("CREATE TABLE lock_rows (id INTEGER PRIMARY KEY, value VARCHAR(32));");
      owner.execute("INSERT INTO lock_rows VALUES (1, 'before');");

      owner.execute("BEGIN;");
      owner.execute("UPDATE lock_rows SET value = 'writer-commit' WHERE id = 1;");
      CountDownLatch readerStarted = new CountDownLatch(1);
      Future<QueryResult> reader =
          executor.submit(
              () -> {
                readerStarted.countDown();
                return waiting.execute("SELECT value FROM lock_rows WHERE id = 1;");
              });
      assertTrue(readerStarted.await(5, TimeUnit.SECONDS));
      assertPending(reader, "reader must wait behind an uncommitted writer");
      owner.execute("COMMIT;");
      assertEquals(
          "writer-commit",
          ((dynamitedb.contracts.storage.TextValue)
                  await(reader, PROCESS_TIMEOUT).rows().getFirst().values().getFirst())
              .value());

      waiting.execute("BEGIN;");
      waiting.execute("SELECT value FROM lock_rows WHERE id = 1;");
      CountDownLatch writerStarted = new CountDownLatch(1);
      Future<QueryResult> writer =
          executor.submit(
              () -> {
                writerStarted.countDown();
                return owner.execute(
                    "UPDATE lock_rows SET value = 'reader-released' WHERE id = 1;");
              });
      assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
      assertPending(writer, "writer must wait behind an open reader transaction");
      waiting.execute("COMMIT;");
      assertEquals(1, await(writer, PROCESS_TIMEOUT).affectedRows());
      assertEquals(
          "reader-released", onlyText(waiting, "SELECT value FROM lock_rows WHERE id = 1;"));
    }
  }

  @ParameterizedTest
  @EnumSource(ProtocolType.class)
  void concurrentIncrementsDoNotLoseCommittedUpdates(ProtocolType protocol) throws Exception {
    try (PackagedServerProcess server = startServer(protocol);
        AcceptanceClient setup = connect(server)) {
      setup.execute("CREATE TABLE acceptance_counter (id INTEGER PRIMARY KEY, value INTEGER);");
      setup.execute("INSERT INTO acceptance_counter VALUES (1, 0);");

      int workerCount = 4;
      int incrementsPerWorker = 12;
      CountDownLatch ready = new CountDownLatch(workerCount);
      CountDownLatch release = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(workerCount);
      try {
        List<Future<Integer>> workers = new ArrayList<>();
        for (int worker = 0; worker < workerCount; worker++) {
          workers.add(
              executor.submit(
                  () -> {
                    try (AcceptanceClient client = connect(server)) {
                      ready.countDown();
                      if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Increment barrier timed out");
                      }
                      int completed = 0;
                      int attempts = 0;
                      while (completed < incrementsPerWorker && attempts < 1000) {
                        attempts++;
                        try {
                          assertAffected(
                              client,
                              "UPDATE acceptance_counter SET value = value + 1 WHERE id = 1;",
                              1);
                          completed++;
                        } catch (DbmsException aborted) {
                          if (aborted.getCode() != ErrorCode.ACCESS_ABORTED) throw aborted;
                        }
                      }
                      return completed;
                    }
                  }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "all TCP clients must connect before racing");
        release.countDown();
        for (Future<Integer> worker : workers) {
          assertEquals(incrementsPerWorker, await(worker, Duration.ofSeconds(60)));
        }
      } finally {
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
      assertEquals(
          workerCount * incrementsPerWorker,
          onlyInt(setup, "SELECT value FROM acceptance_counter WHERE id = 1;"));
    }
  }

  @Test
  void strict2plAbortsOneVictimAndAllowsTheTwoParticipantSurvivorToProgress() throws Exception {
    try (PackagedServerProcess server = startServer(ProtocolType.STRICT_2PL);
        AcceptanceClient first = connect(server);
        AcceptanceClient second = connect(server);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      createDeadlockTable(first, "deadlock_left", "left");
      createDeadlockTable(first, "deadlock_right", "right");
      first.execute("BEGIN;");
      first.execute("UPDATE deadlock_left SET value = 'first-held' WHERE id = 1;");
      second.execute("BEGIN;");
      second.execute("UPDATE deadlock_right SET value = 'second-held' WHERE id = 1;");

      Future<QueryResult> firstWait =
          executor.submit(
              () -> {
                QueryResult result =
                    first.execute("UPDATE deadlock_right SET value = 'first-next' WHERE id = 1;");
                first.execute("COMMIT;");
                return result;
              });
      Future<QueryResult> secondWait =
          executor.submit(
              () -> {
                QueryResult result =
                    second.execute("UPDATE deadlock_left SET value = 'second-next' WHERE id = 1;");
                second.execute("COMMIT;");
                return result;
              });
      Throwable firstFailure = outcome(firstWait);
      Throwable secondFailure = outcome(secondWait);
      assertEquals(1, (firstFailure == null ? 0 : 1) + (secondFailure == null ? 0 : 1));
      assertDeadlockVictim(firstFailure == null ? secondFailure : firstFailure);

      try (AcceptanceClient observer = connect(server)) {
        if (firstFailure == null) {
          assertEquals(
              "first-held", onlyText(observer, "SELECT value FROM deadlock_left WHERE id = 1;"));
          assertEquals(
              "first-next", onlyText(observer, "SELECT value FROM deadlock_right WHERE id = 1;"));
        } else {
          assertEquals(
              "second-next", onlyText(observer, "SELECT value FROM deadlock_left WHERE id = 1;"));
          assertEquals(
              "second-held", onlyText(observer, "SELECT value FROM deadlock_right WHERE id = 1;"));
        }
      }
    }
  }

  @Test
  void strict2plAbortsOneVictimAndAllowsTheThreeParticipantSurvivorsToProgress() throws Exception {
    try (PackagedServerProcess server = startServer(ProtocolType.STRICT_2PL);
        AcceptanceClient first = connect(server);
        AcceptanceClient second = connect(server);
        AcceptanceClient third = connect(server);
        ExecutorService executor = Executors.newFixedThreadPool(3)) {
      createDeadlockTable(first, "deadlock_one", "one");
      createDeadlockTable(first, "deadlock_two", "two");
      createDeadlockTable(first, "deadlock_three", "three");
      first.execute("BEGIN;");
      first.execute("UPDATE deadlock_one SET value = 'first-held' WHERE id = 1;");
      second.execute("BEGIN;");
      second.execute("UPDATE deadlock_two SET value = 'second-held' WHERE id = 1;");
      third.execute("BEGIN;");
      third.execute("UPDATE deadlock_three SET value = 'third-held' WHERE id = 1;");

      List<Future<QueryResult>> pending =
          List.of(
              executor.submit(
                  () ->
                      commitAfterUpdate(
                          first, "UPDATE deadlock_two SET value = 'first-next' WHERE id = 1;")),
              executor.submit(
                  () ->
                      commitAfterUpdate(
                          second, "UPDATE deadlock_three SET value = 'second-next' WHERE id = 1;")),
              executor.submit(
                  () ->
                      commitAfterUpdate(
                          third, "UPDATE deadlock_one SET value = 'third-next' WHERE id = 1;")));
      List<Throwable> failures = new ArrayList<>();
      int victimIndex = -1;
      for (int index = 0; index < pending.size(); index++) {
        Throwable failure = outcome(pending.get(index));
        if (failure != null) {
          failures.add(failure);
          victimIndex = index;
        }
      }
      assertEquals(1, failures.size(), "exactly one transaction must be selected as the victim");
      assertDeadlockVictim(failures.getFirst());

      try (AcceptanceClient observer = connect(server)) {
        if (victimIndex == 0) {
          assertEquals(
              "third-next", onlyText(observer, "SELECT value FROM deadlock_one WHERE id = 1;"));
          assertEquals(
              "second-held", onlyText(observer, "SELECT value FROM deadlock_two WHERE id = 1;"));
          assertEquals(
              "second-next", onlyText(observer, "SELECT value FROM deadlock_three WHERE id = 1;"));
        } else if (victimIndex == 1) {
          assertEquals(
              "third-next", onlyText(observer, "SELECT value FROM deadlock_one WHERE id = 1;"));
          assertEquals(
              "first-next", onlyText(observer, "SELECT value FROM deadlock_two WHERE id = 1;"));
          assertEquals(
              "third-held", onlyText(observer, "SELECT value FROM deadlock_three WHERE id = 1;"));
        } else {
          assertEquals(
              "first-held", onlyText(observer, "SELECT value FROM deadlock_one WHERE id = 1;"));
          assertEquals(
              "first-next", onlyText(observer, "SELECT value FROM deadlock_two WHERE id = 1;"));
          assertEquals(
              "second-next", onlyText(observer, "SELECT value FROM deadlock_three WHERE id = 1;"));
        }
      }
    }
  }

  private static void createDeadlockTable(AcceptanceClient client, String table, String value)
      throws Exception {
    client.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, value VARCHAR(32));");
    client.execute("INSERT INTO " + table + " VALUES (1, '" + value + "');");
  }

  private static void assertPending(Future<?> future, String message) throws Exception {
    assertThrows(TimeoutException.class, () -> future.get(300, TimeUnit.MILLISECONDS), message);
  }

  private static Throwable outcome(Future<?> future) throws Exception {
    try {
      future.get(20, TimeUnit.SECONDS);
      return null;
    } catch (ExecutionException failure) {
      return failure.getCause();
    }
  }

  private static void assertDeadlockVictim(Throwable failure) {
    DbmsException aborted = assertInstanceOf(DbmsException.class, failure);
    assertEquals(ErrorCode.ACCESS_ABORTED, aborted.getCode());
  }

  private static QueryResult commitAfterUpdate(AcceptanceClient client, String sql)
      throws Exception {
    QueryResult result = client.execute(sql);
    client.execute("COMMIT;");
    return result;
  }
}
