package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableWalTest {
  @TempDir Path directory;
  private static final RecoveryHighWater EMPTY =
      new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());

  @Test
  void loggedChangesAndCommitSurviveReopenWithAllFields() {
    UUID lineage = UUID.randomUUID();
    LogSequenceNumber changeLsn;
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      var wal = new WalService(new CommitClock(), log);
      var tx = new TransactionId(1);
      wal.begin(tx, 1);
      changeLsn = wal.appendChange(change(tx, 1, RecordImage.absent(), present(1, 2, 3)));
      assertEquals(2, changeLsn.value());
      wal.ensureDurable(changeLsn);
      assertEquals(changeLsn.value(), log.durableLsn());
      wal.commitDurably(tx);
      assertEquals(3, log.durableLsn());
      assertThrows(
          DbmsException.class, () -> wal.appendChange(change(tx, 1, present(1), present(2))));
    }
    try (var reopened = LogManager.openExisting(directory, lineage, EMPTY)) {
      var records = toList(reopened.scanFrom(new LogSequenceNumber(1)));
      assertEquals(3, records.size());
      var saved = (ChangeLogRecord) records.get(1);
      assertEquals(new TransactionId(1), saved.change.tx());
      assertEquals("items", saved.change.table());
      assertEquals(new RecordId(1, 5, 1, 1), saved.change.rid());
      assertFalse(saved.change.before().exists());
      assertEquals(present(1, 2, 3), saved.change.after());
      assertEquals(Optional.of(new LogSequenceNumber(1)), saved.prevLsn);
      assertEquals(Optional.of(new LogSequenceNumber(2)), records.get(2).prevLsn);
      assertTrue(reopened.readHighWater().maxCommitTime().isPresent());
      var wal = new WalService(new CommitClock(), reopened);
      wal.begin(new TransactionId(2), 2);
      assertEquals(4, reopened.appendedLsn());
    }
  }

  @Test
  void inspectionIsFilteredAndReadOnlyEvenWhenWalIsEmpty() throws Exception {
    UUID lineage = UUID.randomUUID();
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      var wal = new WalService(new CommitClock(), log);
      assertTrue(wal.readLog(new LogFilter(Optional.empty(), Optional.empty())).lines().isEmpty());
      wal.begin(new TransactionId(1), 1);
      wal.appendChange(change(new TransactionId(1), 1, present(1), present(2)));
      wal.begin(new TransactionId(2), 2);
      long beforeLsn = log.appendedLsn();
      var filtered =
          wal.readLog(
              new LogFilter(
                  Optional.of(new TransactionId(1)), Optional.of(new LogSequenceNumber(2))));
      assertEquals(1, filtered.lines().size());
      assertTrue(filtered.lines().getFirst().contains("UPDATE"));
      assertEquals(
          List.of(2L, 1L),
          toList(log.reverseByTx(new TransactionId(1))).stream().map(r -> r.lsn.value()).toList());
      assertEquals(beforeLsn, log.appendedLsn());
    }
  }

  @Test
  void allWritersShareTheSameSequenceUnderConcurrency() throws Exception {
    UUID lineage = UUID.randomUUID();
    int workers = 12;
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      var wal = new WalService(new CommitClock(), log);
      var start = new CountDownLatch(1);
      var pool = Executors.newFixedThreadPool(workers);
      try {
        var tasks = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 1; i <= workers; i++) {
          int id = i;
          tasks.add(
              pool.submit(
                  () -> {
                    try {
                      start.await();
                      var tx = new TransactionId(id);
                      wal.begin(tx, id);
                      wal.appendChange(change(tx, id, RecordImage.absent(), present(id)));
                      wal.commitDurably(tx);
                    } catch (InterruptedException failure) {
                      Thread.currentThread().interrupt();
                      throw new AssertionError(failure);
                    }
                  }));
        }
        start.countDown();
        for (var task : tasks) task.get(10, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
      }
      var records = toList(log.scanFrom(new LogSequenceNumber(1)));
      assertEquals(workers * 3, records.size());
      assertEquals(
          records.size(), new HashSet<>(records.stream().map(r -> r.lsn.value()).toList()).size());
      for (int i = 0; i < records.size(); i++) assertEquals(i + 1, records.get(i).lsn.value());
      assertEquals(records.size(), log.durableLsn());
    }
    try (var reopened = LogManager.openExisting(directory, lineage, EMPTY)) {
      assertEquals(workers * 3, toList(reopened.scanFrom(new LogSequenceNumber(1))).size());
    }
  }

  @Test
  void codecCoversCompensationAndCheckpointWithoutLosingFields() {
    var codec = new LogRecordCodec();
    var tx = new TransactionId(1);
    var lineage = UUID.randomUUID();
    var clr =
        new CompensationLogRecord(
            new LogSequenceNumber(5),
            tx,
            Optional.of(new LogSequenceNumber(4)),
            lineage,
            new LogSequenceNumber(3),
            Optional.of(new LogSequenceNumber(2)),
            new RecoveryMutation(new RecordId(1, 5, 1, 1), present(2)));
    var decoded = (CompensationLogRecord) codec.decode(codec.encode(clr));
    assertEquals(clr.compensates, decoded.compensates);
    assertEquals(clr.undoNext, decoded.undoNext);
    assertEquals(clr.mutation, decoded.mutation);
    var checkpoint =
        new CheckpointLogRecord(
            new LogSequenceNumber(6),
            lineage,
            UUID.randomUUID(),
            CheckpointEvent.END,
            new LogSequenceNumber(5));
    var restored = (CheckpointLogRecord) codec.decode(codec.encode(checkpoint));
    assertEquals(checkpoint.checkpointId, restored.checkpointId);
    assertEquals(checkpoint.throughLsn, restored.throughLsn);
    byte[] corrupted = codec.encode(checkpoint).copy();
    corrupted[10] ^= 1;
    assertFalse(codec.verify(ImmutableBytes.copyOf(corrupted)));
  }

  @Test
  void tornTailIsRepairedButCompleteCorruptionFailsClosed() throws Exception {
    UUID lineage = UUID.randomUUID();
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      log.begin(new TransactionId(1), 1);
      log.flush(new LogSequenceNumber(1));
    }
    Path path = LogFile.path(directory, lineage);
    Files.write(path, new byte[] {0x44, 0x57}, StandardOpenOption.APPEND);
    try (var recovered = LogManager.openExisting(directory, lineage, EMPTY)) {
      assertEquals(1, recovered.appendedLsn());
    }
    byte[] bytes = Files.readAllBytes(path);
    bytes[45] ^= 1;
    Files.write(path, bytes);
    DbmsException failure =
        assertThrows(DbmsException.class, () -> LogManager.openExisting(directory, lineage, EMPTY));
    assertEquals(ErrorCode.CORRUPT_LOG, failure.getCode());
    assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(path)));
  }

  @Test
  void invalidFrameLengthCannotEraseAValidPrefix() throws Exception {
    UUID lineage = UUID.randomUUID();
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      log.begin(new TransactionId(1), 1);
      log.begin(new TransactionId(2), 2);
      log.flush(new LogSequenceNumber(2));
    }
    Path path = LogFile.path(directory, lineage);
    byte[] damaged = Files.readAllBytes(path);
    damaged[40] ^= 1; // First frame's length; its header checksum must reject the change.
    Files.write(path, damaged);
    assertEquals(
        ErrorCode.CORRUPT_LOG,
        assertThrows(DbmsException.class, () -> LogManager.openExisting(directory, lineage, EMPTY))
            .getCode());
    assertTrue(java.util.Arrays.equals(damaged, Files.readAllBytes(path)));
  }

  @Test
  void compensationCheckpointAndNewLineageShareWalRules() {
    UUID lineage = UUID.randomUUID();
    UUID nextLineage = UUID.randomUUID();
    var tx = new TransactionId(1);
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      log.begin(tx, 1);
      LogSequenceNumber change = log.appendChange(change(tx, 1, RecordImage.absent(), present(7)));
      var compensation =
          log.appendCompensation(
              tx,
              change,
              Optional.of(new LogSequenceNumber(1)),
              new RecoveryMutation(new RecordId(1, 5, 1, 1), RecordImage.absent()));
      assertEquals(3, compensation.value());
      log.finishAbort(tx);
      UUID checkpointId = UUID.randomUUID();
      log.appendCheckpoint(checkpointId, CheckpointEvent.BEGIN, new LogSequenceNumber(4));
      log.appendCheckpoint(checkpointId, CheckpointEvent.END, new LogSequenceNumber(4));
      assertEquals(4, log.durableLsn());
      var high = log.readHighWater();
      var base =
          new SealedGeneration(
              UUID.randomUUID(), new RecoveryBoundary(new LogSequenceNumber(6), Optional.empty()));
      var stale =
          new RecoveryHighWater(
              high.lsn(), new TransactionId(0), high.maxTimestamp(), high.maxCommitTime());
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(DbmsException.class, () -> log.prepareLineage(nextLineage, base, stale))
              .getCode());
      log.prepareLineage(nextLineage, base, high);
      assertEquals(6, log.durableLsn());
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(
                  DbmsException.class,
                  () ->
                      log.activate(
                          new GenerationManifest(
                              base.id(), nextLineage, new LogSequenceNumber(5), high)))
              .getCode());
      log.activate(new GenerationManifest(base.id(), nextLineage, new LogSequenceNumber(6), high));
      assertEquals(6, log.appendedLsn());
      log.begin(new TransactionId(2), 2);
      assertEquals(7, log.appendedLsn());
      assertEquals(6, toList(log.scanHistory(lineage, new LogSequenceNumber(1))).size());
    }
  }

  @Test
  void committedPrefixSurvivesAbruptProcessExit() throws Exception {
    String classpath =
        Path.of(WalCrashProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator
            + Path.of(LogManager.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator
            + Path.of(
                LogSequenceNumber.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
    for (String mode : List.of("commit", "uncommitted")) {
      UUID lineage = UUID.randomUUID();
      var process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  classpath,
                  WalCrashProbe.class.getName(),
                  directory.toString(),
                  lineage.toString(),
                  mode)
              .redirectErrorStream(true)
              .start();
      boolean finished = process.waitFor(10, TimeUnit.SECONDS);
      if (!finished) process.destroyForcibly();
      assertTrue(finished);
      assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
      try (var log = LogManager.openExisting(directory, lineage, EMPTY)) {
        var records = toList(log.scanFrom(new LogSequenceNumber(1)));
        assertEquals(mode.equals("commit") ? 3 : 2, records.size());
        assertEquals(
            mode.equals("commit"),
            records.stream()
                .anyMatch(
                    r ->
                        r instanceof TransactionEventLogRecord event
                            && event.event == TxEvent.COMMIT));
      }
    }
  }

  @Test
  void insertUpdateDeleteRemainInspectableAfterReopen() {
    UUID lineage = UUID.randomUUID();
    var tx = new TransactionId(1);
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      var wal = new WalService(new CommitClock(), log);
      wal.begin(tx, 1);
      wal.appendChange(change(tx, 1, RecordImage.absent(), present(1)));
      wal.appendChange(change(tx, 1, present(1), present(2)));
      wal.appendChange(change(tx, 1, present(2), RecordImage.absent()));
      wal.commitDurably(tx);
    }
    try (var log = LogManager.openExisting(directory, lineage, EMPTY)) {
      var lines =
          new WalService(new CommitClock(), log)
              .readLog(new LogFilter(Optional.of(tx), Optional.of(new LogSequenceNumber(2))))
              .lines();
      assertEquals(4, lines.size());
      assertTrue(lines.get(0).contains("INSERT"));
      assertTrue(lines.get(1).contains("UPDATE"));
      assertTrue(lines.get(2).contains("DELETE"));
      assertTrue(lines.get(3).contains("COMMIT"));
    }
  }

  @Test
  void missingWalAndSecondWriterFailWithoutCreatingOrChangingData() throws Exception {
    UUID lineage = UUID.randomUUID();
    Path path = LogFile.path(directory, lineage);
    assertEquals(
        ErrorCode.WAL_FAILURE,
        assertThrows(DbmsException.class, () -> LogManager.openExisting(directory, lineage, EMPTY))
            .getCode());
    assertFalse(Files.exists(path));
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      assertEquals(0, log.appendedLsn());
      long beforeLsn = log.appendedLsn();
      assertEquals(
          ErrorCode.WAL_FAILURE,
          assertThrows(
                  DbmsException.class, () -> LogManager.openExisting(directory, lineage, EMPTY))
              .getCode());
      assertEquals(beforeLsn, log.appendedLsn());
    }
    try (var reopened = LogManager.openExisting(directory, lineage, EMPTY)) {
      assertEquals(0, reopened.appendedLsn());
    }
  }

  @Test
  void rollbackRejectsWrongImageAndFurtherChanges() {
    UUID lineage = UUID.randomUUID();
    var tx = new TransactionId(1);
    var rid = new RecordId(1, 5, 1, 1);
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      log.begin(tx, 1);
      var original = log.appendChange(change(tx, 1, RecordImage.absent(), present(7)));
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(
                  DbmsException.class,
                  () ->
                      log.appendCompensation(
                          tx,
                          original,
                          Optional.of(new LogSequenceNumber(1)),
                          new RecoveryMutation(rid, present(9))))
              .getCode());
      assertEquals(2, log.appendedLsn());
      log.appendCompensation(
          tx,
          original,
          Optional.of(new LogSequenceNumber(1)),
          new RecoveryMutation(rid, RecordImage.absent()));
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(
                  DbmsException.class,
                  () -> log.appendChange(change(tx, 2, RecordImage.absent(), present(8))))
              .getCode());
      assertTrue(
          new WalService(new CommitClock(), log)
              .readLog(new LogFilter(Optional.of(tx), Optional.empty()))
              .lines()
              .getLast()
              .contains("undoNext=1 rid="));
      log.finishAbort(tx);
    }
  }

  @Test
  void codecReportsInvalidCommitTimeAsCorruptLog() {
    var codec = new LogRecordCodec();
    var record =
        new TransactionEventLogRecord(
            new LogSequenceNumber(1),
            new TransactionId(1),
            Optional.empty(),
            UUID.randomUUID(),
            TxEvent.COMMIT,
            Optional.empty(),
            Optional.of(java.time.Instant.EPOCH));
    byte[] frame = codec.encode(record).copy();
    int payloadSize = java.nio.ByteBuffer.wrap(frame, 4, 4).getInt();
    java.nio.ByteBuffer.wrap(frame, frame.length - 8, 4).putInt(1_000_000_000);
    var crc = new java.util.zip.CRC32();
    crc.update(frame, 12, payloadSize);
    java.nio.ByteBuffer.wrap(frame, frame.length - 4, 4).putInt((int) crc.getValue());
    assertEquals(
        ErrorCode.CORRUPT_LOG,
        assertThrows(DbmsException.class, () -> codec.decode(ImmutableBytes.copyOf(frame)))
            .getCode());
  }

  @Test
  void oversizedTableNameDoesNotConsumeAnLsn() {
    try (var log = LogManager.createNew(directory, UUID.randomUUID(), EMPTY)) {
      var tx = new TransactionId(1);
      log.begin(tx, 1);
      var oversized =
          new ChangeRecord(
              tx, "x".repeat(70_000), new RecordId(1, 5, 1, 1), RecordImage.absent(), present(1));
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(DbmsException.class, () -> log.appendChange(oversized)).getCode());
      assertEquals(1, log.appendedLsn());
    }
  }

  @Test
  void commitClockContinuesPastPersistedTimeWhenWallClockMovesBackward() {
    var clock = new CommitClock();
    var saved = java.time.Instant.parse("2026-11-04T12:00:00.123456789Z");
    clock.seed(saved);
    assertEquals(saved.plusNanos(1), clock.assign(java.time.Instant.EPOCH));
    assertEquals(saved.plusNanos(2), clock.assign(java.time.Instant.EPOCH));
  }

  @Test
  void activeTransactionCannotPrepareNewLineage() {
    try (var log = LogManager.createNew(directory, UUID.randomUUID(), EMPTY)) {
      log.begin(new TransactionId(1), 1);
      var base =
          new SealedGeneration(
              UUID.randomUUID(), new RecoveryBoundary(new LogSequenceNumber(1), Optional.empty()));
      UUID next = UUID.randomUUID();
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(
                  DbmsException.class, () -> log.prepareLineage(next, base, log.readHighWater()))
              .getCode());
      assertFalse(Files.exists(LogFile.path(directory, next)));
    }
  }

  private static ChangeRecord change(
      TransactionId tx, int slot, RecordImage before, RecordImage after) {
    return new ChangeRecord(tx, "items", new RecordId(1, 5, slot, 1), before, after);
  }

  private static RecordImage present(int... values) {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
    return new RecordImage(true, ImmutableBytes.copyOf(bytes));
  }

  private static List<LogRecord> toList(java.util.Iterator<LogRecord> iterator) {
    var records = new ArrayList<LogRecord>();
    iterator.forEachRemaining(records::add);
    return records;
  }
}
