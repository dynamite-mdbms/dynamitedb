package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.frm.CheckpointEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryAcceptanceTest extends PackagedServerAcceptanceSupport {
  @ParameterizedTest
  @EnumSource(ProtocolType.class)
  void abruptRestartRecoversCommittedAbortedAndActiveMixedDmlIdempotently(ProtocolType protocol)
      throws Exception {
    Path data = directory.resolve("crash-data");
    try (PackagedServerProcess server = startServer(data, protocol);
        AcceptanceClient active = connect(server)) {
      try (AcceptanceClient committed = connect(server);
          AcceptanceClient aborted = connect(server)) {
        assertAffected(
            committed, "INSERT INTO student VALUES (93001, 'committed-insert', 3.0, 'A');", 1);
        assertAffected(
            committed, "UPDATE student SET name = 'committed-update' WHERE student_id = 1;", 1);
        assertAffected(committed, "DELETE FROM student WHERE student_id = 2;", 1);

        aborted.execute("BEGIN;");
        aborted.execute("INSERT INTO student VALUES (93002, 'aborted-insert', 3.0, 'A');");
        aborted.execute("UPDATE student SET name = 'aborted-update' WHERE student_id = 3;");
        aborted.execute("DELETE FROM student WHERE student_id = 4;");
        aborted.execute("ABORT;");
      }

      active.execute("BEGIN;");
      active.execute("INSERT INTO student VALUES (93003, 'active-insert', 3.0, 'A');");
      active.execute("UPDATE student SET name = 'active-update' WHERE student_id = 5;");
      active.execute("DELETE FROM student WHERE student_id = 6;");
      server.killForcibly();
      active.disconnectAbruptly();
    }

    try (PackagedServerProcess recovered = startServer(data, protocol);
        AcceptanceClient client = connect(recovered)) {
      assertRecoveredMixedDml(client);
    }

    try (PackagedServerProcess recoveredAgain = startServer(data, protocol);
        AcceptanceClient client = connect(recoveredAgain)) {
      assertRecoveredMixedDml(client);
    }
  }

  @Test
  void periodicCheckpointWaitsForActiveTransactionAndCompletedCheckpointSurvivesRestart()
      throws Exception {
    Path data = directory.resolve("checkpoint-data");
    long checkpointEnds;
    try (PackagedServerProcess server = startServer(data, ProtocolType.STRICT_2PL, 1);
        AcceptanceClient client = connect(server)) {
      awaitCondition(
          () -> checkpointCount(data, CheckpointEvent.END) > 0,
          Duration.ofSeconds(20),
          "periodic checkpoint did not complete");
      long beginsBeforeTransaction = checkpointCount(data, CheckpointEvent.BEGIN);
      long endsBeforeTransaction = checkpointCount(data, CheckpointEvent.END);

      client.execute("BEGIN;");
      client.execute("UPDATE student SET name = 'checkpoint-committed' WHERE student_id = 7;");
      Thread.sleep(1_500);
      assertEquals(beginsBeforeTransaction, checkpointCount(data, CheckpointEvent.BEGIN));
      assertEquals(endsBeforeTransaction, checkpointCount(data, CheckpointEvent.END));

      client.execute("COMMIT;");
      awaitCondition(
          () -> checkpointCount(data, CheckpointEvent.END) > endsBeforeTransaction,
          Duration.ofSeconds(20),
          "checkpoint was not admitted after the transaction finished");
      checkpointEnds = checkpointCount(data, CheckpointEvent.END);
    }

    assertTrue(checkpointEnds > 0);
    try (PackagedServerProcess restarted = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(restarted)) {
      assertEquals(
          "checkpoint-committed",
          onlyText(client, "SELECT name FROM student WHERE student_id = 7;"));
    }
  }

  @Test
  void startupRepairsAnIncompleteWalTailAndKeepsCommittedData() throws Exception {
    Path data = directory.resolve("tail-data");
    try (PackagedServerProcess server = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(server)) {
      client.execute("INSERT INTO student VALUES (93101, 'tail-committed', 3.0, 'A');");
    }

    Path wal = PersistentDataFiles.activeWal(data);
    long validSize = Files.size(wal);
    Files.write(wal, new byte[] {0x44, 0x42, 0x4D, 0x53, 0x01}, StandardOpenOption.APPEND);
    assertEquals(validSize + 5, Files.size(wal));

    try (PackagedServerProcess recovered = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(recovered)) {
      assertEquals(
          1,
          client.execute("SELECT student_id FROM student WHERE student_id = 93101;").rows().size());
    }
    PersistentDataFiles.assertWalComplete(data);
  }

  @Test
  void startupRejectsMiddleWalCorruptionBeforeListening() throws Exception {
    Path data = directory.resolve("corrupt-data");
    try (PackagedServerProcess server = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(server)) {
      client.execute("INSERT INTO student VALUES (93102, 'corruption-seed', 3.0, 'A');");
    }
    PersistentDataFiles.corruptMiddleFrame(data);
    assertStartupRefused(data, ProtocolType.STRICT_2PL, "WAL");
  }

  @Test
  void startupRefusesAnActiveManifestWithoutItsWal() throws Exception {
    Path data = directory.resolve("missing-wal-data");
    try (PackagedServerProcess server = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(server)) {
      client.execute("INSERT INTO student VALUES (93103, 'missing-wal-seed', 3.0, 'A');");
    }
    Files.delete(PersistentDataFiles.activeWal(data));
    assertStartupRefused(data, ProtocolType.STRICT_2PL, "no WAL");
  }

  @Test
  void aDataDirectoryHasOnlyOneLivePackagedServerOwner() throws Exception {
    Path data = directory.resolve("exclusive-data");
    try (PackagedServerProcess owner = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(owner)) {
      assertStartupRefused(data, ProtocolType.STRICT_2PL, "WAL");
      assertEquals(50, client.execute("SELECT student_id FROM student;").rows().size());
    }
  }

  private static void assertRecoveredMixedDml(AcceptanceClient client) throws Exception {
    assertEquals(
        "committed-update", onlyText(client, "SELECT name FROM student WHERE student_id = 1;"));
    assertNoRows(client, "SELECT student_id FROM student WHERE student_id = 2;");
    assertEquals("Student 3", onlyText(client, "SELECT name FROM student WHERE student_id = 3;"));
    assertEquals("Student 4", onlyText(client, "SELECT name FROM student WHERE student_id = 4;"));
    assertEquals("Student 5", onlyText(client, "SELECT name FROM student WHERE student_id = 5;"));
    assertEquals("Student 6", onlyText(client, "SELECT name FROM student WHERE student_id = 6;"));
    assertEquals(
        "committed-insert", onlyText(client, "SELECT name FROM student WHERE student_id = 93001;"));
    assertNoRows(client, "SELECT student_id FROM student WHERE student_id = 93002;");
    assertNoRows(client, "SELECT student_id FROM student WHERE student_id = 93003;");
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
    throw new IOException("Could not read a complete WAL snapshot", lastReadFailure);
  }

  private static void assertStartupRefused(Path data, ProtocolType protocol, String logFragment)
      throws Exception {
    try (PackagedServerProcess process =
        PackagedServerProcess.launch(data, argument(protocol), 60)) {
      assertThrows(
          IOException.class,
          () -> process.awaitReady(Duration.ofSeconds(15)),
          "server must exit before publishing its readiness line");
      assertTrue(process.awaitExit(Duration.ofSeconds(10)), process.output());
      assertFalse(process.output().contains("listening on 127.0.0.1:"), process.output());
      assertTrue(process.output().contains(logFragment), process.output());
    }
  }
}
