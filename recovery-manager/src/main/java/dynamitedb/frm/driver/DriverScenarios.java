package dynamitedb.frm.driver;

import static dynamitedb.frm.driver.DriverAssertions.check;
import static dynamitedb.frm.driver.DriverAssertions.image;
import static dynamitedb.frm.driver.DriverAssertions.requireSuccess;

import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.LogView;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.testing.TestResult;
import dynamitedb.contracts.transaction.TransactionId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Semua skenario driver; dipisah dari entry point biar nggak jadi satu file raksasa. */
final class DriverScenarios {
  private static final RecordId COMMITTED_NOT_FLUSHED = new RecordId(1, 1, 1, 1);
  private static final RecordId COMMITTED_FLUSHED = new RecordId(1, 1, 2, 1);
  private static final RecordId LOSER_FLUSHED = new RecordId(1, 1, 3, 1);
  private static final RecordId ABORTED = new RecordId(1, 1, 4, 1);
  private static final RecordId MIXED_DML = new RecordId(2, 1, 1, 1);
  private static final RecordId DELETED_RECORD = new RecordId(2, 1, 2, 1);
  private static final RecordId CHECKPOINT_BEFORE = new RecordId(2, 2, 1, 1);
  private static final RecordId CHECKPOINT_AFTER = new RecordId(2, 2, 2, 1);

  private final Path root;

  DriverScenarios(Path root) {
    this.root = root;
  }

  TestResult wal() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "wal")) {
      TransactionId tx = new TransactionId(1);
      harness.wal.begin(tx, 1);
      LogSequenceNumber change =
          harness.wal.appendChange(
              new ChangeRecord(tx, "items", COMMITTED_NOT_FLUSHED, RecordImage.absent(), image(1)));
      harness.wal.ensureDurable(change);
      harness.wal.commitDurably(tx);
      check(harness.log.durableLsn() >= change.value(), "WAL change belum durable");
      LogView log = harness.wal.readLog(new LogFilter(Optional.empty(), Optional.empty()));
      check(
          log.lines().stream().anyMatch(line -> line.contains("INSERT") && line.contains("tx=T1")),
          "log nggak nunjukin INSERT: " + log.lines());
      return new TestResult(true, "wal: BEGIN, INSERT, COMMIT durable");
    }
  }

  TestResult logInspection() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "log-inspection")) {
      TransactionId first = new TransactionId(1);
      TransactionId second = new TransactionId(2);
      harness.wal.begin(first, 1);
      LogSequenceNumber firstLsn =
          harness.wal.appendChange(
              new ChangeRecord(
                  first, "items", COMMITTED_NOT_FLUSHED, RecordImage.absent(), image(1)));
      harness.wal.commitDurably(first);
      harness.wal.begin(second, 2);
      harness.wal.appendChange(
          new ChangeRecord(second, "items", COMMITTED_FLUSHED, RecordImage.absent(), image(2)));
      harness.wal.commitDurably(second);

      LogView filtered =
          harness.wal.readLog(new LogFilter(Optional.of(first), Optional.of(firstLsn)));
      check(!filtered.lines().isEmpty(), "log hasil filter kosong");
      check(
          filtered.lines().stream().allMatch(line -> line.contains("tx=T1")),
          "filter bocor transaksi lain: " + filtered.lines());
      long before = harness.log.appendedLsn();
      harness.wal.readLog(new LogFilter(Optional.empty(), Optional.empty()));
      check(before == harness.log.appendedLsn(), "baca log malah ngubah isinya");
      return new TestResult(true, "log-inspection: filter dan read-only aman");
    }
  }

  TestResult rollback() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "rollback")) {
      TransactionId tx = new TransactionId(1);
      harness.wal.begin(tx, 1);
      LogSequenceNumber lsn =
          harness.wal.appendChange(
              new ChangeRecord(tx, "items", LOSER_FLUSHED, RecordImage.absent(), image(7)));
      harness.store.applyRecovery(new RecoveryMutation(LOSER_FLUSHED, image(7)), lsn);
      harness.undo.rollbackCompletely(tx);
      check(!harness.store.readRecord(LOSER_FLUSHED).exists(), "record masih ada setelah rollback");
      check(
          harness.wal.readLog(new LogFilter(Optional.of(tx), Optional.empty())).lines().stream()
              .anyMatch(line -> line.contains("ABORT_COMPLETE")),
          "ABORT_COMPLETE nggak ketulis");
      check(
          harness.log.appendedLsn() == harness.log.durableLsn(),
          "masih ada WAL yang belum durable");
      return new TestResult(true, "rollback: undo, CLR, ABORT_COMPLETE durable");
    }
  }

  TestResult checkpoint() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "checkpoint")) {
      TransactionId tx = new TransactionId(1);
      harness.wal.begin(tx, 1);
      LogSequenceNumber lsn =
          harness.wal.appendChange(
              new ChangeRecord(tx, "items", COMMITTED_FLUSHED, RecordImage.absent(), image(1)));
      harness.store.applyRecovery(new RecoveryMutation(COMMITTED_FLUSHED, image(1)), lsn);
      harness.wal.commitDurably(tx);
      CheckpointResult result = harness.checkpoint.checkpoint();
      check(result.checkpointLsn().value() > 0, "checkpoint nggak balikin LSN END");
      check(
          harness.wal.readLog(new LogFilter(Optional.empty(), Optional.empty())).lines().stream()
              .anyMatch(line -> line.contains("CHECKPOINT_END")),
          "marker CHECKPOINT_END hilang");
      return new TestResult(true, "checkpoint: BEGIN/END dan flush jalan");
    }
  }

  TestResult multiTransaction() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "multi-transaction")) {
      seedMultiTransaction(harness);
    }
    try (RecoveryHarness reopened = RecoveryHarness.open(root, "multi-transaction")) {
      requireSuccess(reopened.initializer.openExisting(), "recovery multi-transaction");
      verifyMultiTransaction(reopened);
      return new TestResult(true, "multi-transaction: redo, undo, abort, high-water benar");
    }
  }

  TestResult mixedDml() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "mixed-dml")) {
      TransactionId first = new TransactionId(1);
      harness.wal.begin(first, 1);
      harness.wal.appendChange(
          new ChangeRecord(first, "items", MIXED_DML, RecordImage.absent(), image(1)));
      harness.wal.commitDurably(first);

      TransactionId second = new TransactionId(2);
      harness.wal.begin(second, 2);
      LogSequenceNumber firstUpdate =
          harness.wal.appendChange(
              new ChangeRecord(second, "items", MIXED_DML, image(1), image(2)));
      harness.store.applyRecovery(new RecoveryMutation(MIXED_DML, image(2)), firstUpdate);
      LogSequenceNumber secondUpdate =
          harness.wal.appendChange(
              new ChangeRecord(second, "items", MIXED_DML, image(2), image(3)));
      harness.store.applyRecovery(new RecoveryMutation(MIXED_DML, image(3)), secondUpdate);
      harness.wal.commitDurably(second);

      TransactionId loser = new TransactionId(3);
      harness.wal.begin(loser, 3);
      LogSequenceNumber loserUpdate =
          harness.wal.appendChange(new ChangeRecord(loser, "items", MIXED_DML, image(3), image(4)));
      harness.store.applyRecovery(new RecoveryMutation(MIXED_DML, image(4)), loserUpdate);
      harness.wal.ensureDurable(loserUpdate);

      TransactionId inserter = new TransactionId(4);
      harness.wal.begin(inserter, 4);
      harness.wal.appendChange(
          new ChangeRecord(inserter, "items", DELETED_RECORD, RecordImage.absent(), image(9)));
      harness.wal.commitDurably(inserter);

      TransactionId deleter = new TransactionId(5);
      harness.wal.begin(deleter, 5);
      harness.wal.appendChange(
          new ChangeRecord(deleter, "items", DELETED_RECORD, image(9), RecordImage.absent()));
      harness.wal.commitDurably(deleter);
    }

    try (RecoveryHarness reopened = RecoveryHarness.open(root, "mixed-dml")) {
      requireSuccess(reopened.initializer.openExisting(), "recovery mixed DML");
      check(
          image(3).equals(reopened.store.readRecord(MIXED_DML)),
          "INSERT/UPDATE chain nggak balik ke nilai commit terakhir");
      check(!reopened.store.readRecord(DELETED_RECORD).exists(), "DELETE committed nggak di-redo");
      return new TestResult(true, "mixed-dml: INSERT, UPDATE chain, DELETE, loser undo jalan");
    }
  }

  TestResult checkpointRecovery() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "checkpoint-recovery")) {
      TransactionId before = new TransactionId(1);
      harness.wal.begin(before, 1);
      LogSequenceNumber beforeLsn =
          harness.wal.appendChange(
              new ChangeRecord(before, "items", CHECKPOINT_BEFORE, RecordImage.absent(), image(1)));
      harness.store.applyRecovery(new RecoveryMutation(CHECKPOINT_BEFORE, image(1)), beforeLsn);
      harness.wal.commitDurably(before);
      harness.checkpoint.checkpoint();

      TransactionId after = new TransactionId(2);
      harness.wal.begin(after, 2);
      harness.wal.appendChange(
          new ChangeRecord(after, "items", CHECKPOINT_AFTER, RecordImage.absent(), image(2)));
      harness.wal.commitDurably(after);

      TransactionId loser = new TransactionId(3);
      harness.wal.begin(loser, 3);
      LogSequenceNumber loserLsn =
          harness.wal.appendChange(
              new ChangeRecord(loser, "items", CHECKPOINT_BEFORE, image(1), image(9)));
      harness.store.applyRecovery(new RecoveryMutation(CHECKPOINT_BEFORE, image(9)), loserLsn);
      harness.wal.ensureDurable(loserLsn);
    }

    try (RecoveryHarness reopened = RecoveryHarness.open(root, "checkpoint-recovery")) {
      requireSuccess(reopened.initializer.openExisting(), "recovery checkpoint");
      check(
          image(1).equals(reopened.store.readRecord(CHECKPOINT_BEFORE)),
          "undo setelah checkpoint gagal");
      check(
          image(2).equals(reopened.store.readRecord(CHECKPOINT_AFTER)),
          "redo setelah checkpoint gagal");
      return new TestResult(true, "checkpoint-recovery: checkpoint selesai dipakai, loser di-undo");
    }
  }

  TestResult restartTwice() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "restart-twice")) {
      seedMultiTransaction(harness);
    }
    try (RecoveryHarness first = RecoveryHarness.open(root, "restart-twice")) {
      requireSuccess(first.initializer.openExisting(), "restart pertama");
      verifyMultiTransaction(first);
    }
    try (RecoveryHarness second = RecoveryHarness.open(root, "restart-twice")) {
      requireSuccess(second.initializer.openExisting(), "restart kedua");
      verifyMultiTransaction(second);
      return new TestResult(true, "restart-twice: recovery idempoten, nggak ada record dobel");
    }
  }

  TestResult crash() {
    Path directory = root.resolve("crash");
    RecoveryHarness.deleteDirectory(directory);
    try {
      Files.createDirectories(directory);
      Process process = startCrashChild(directory);
      boolean finished = process.waitFor(30, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return new TestResult(false, "crash: child process nggak selesai");
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        return new TestResult(false, "crash: child process gagal: " + output.trim());
      }
    } catch (IOException failure) {
      return new TestResult(false, "crash: gagal start child process: " + failure.getMessage());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return new TestResult(false, "crash: keinterupsi nunggu child");
    }

    try (RecoveryHarness reopened = RecoveryHarness.open(root, "crash")) {
      requireSuccess(reopened.initializer.openExisting(), "recovery crash");
      check(
          reopened.store.readRecord(COMMITTED_NOT_FLUSHED).exists(),
          "redo crash kehilangan committed record");
      check(
          reopened.store.readRecord(COMMITTED_FLUSHED).exists(),
          "committed record yang udah flush hilang");
      check(!reopened.store.readRecord(LOSER_FLUSHED).exists(), "undo crash ninggalin loser");
      check(!reopened.store.readRecord(ABORTED).exists(), "record aborted masih ada");
      check(reopened.highWater().maxTx().value() >= 4, "high-water nggak balik ke CCM");
      return new TestResult(true, "crash: halt antar-JVM, reopen, redo, undo sukses");
    }
  }

  TestResult historical() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "historical")) {
      TransactionId first = new TransactionId(1);
      harness.wal.begin(first, 1);
      LogSequenceNumber firstLsn =
          harness.wal.appendChange(
              new ChangeRecord(
                  first, "items", COMMITTED_NOT_FLUSHED, RecordImage.absent(), image(1)));
      harness.store.applyRecovery(new RecoveryMutation(COMMITTED_NOT_FLUSHED, image(1)), firstLsn);
      harness.wal.commitDurably(first);

      TransactionId second = new TransactionId(2);
      harness.wal.begin(second, 2);
      LogSequenceNumber secondLsn =
          harness.wal.appendChange(
              new ChangeRecord(second, "items", COMMITTED_FLUSHED, RecordImage.absent(), image(2)));
      harness.store.applyRecovery(new RecoveryMutation(COMMITTED_FLUSHED, image(2)), secondLsn);
      harness.wal.commitDurably(second);

      RecoveryResult result =
          harness.historical.recoverTo(new RecoveryTarget(Optional.empty(), Optional.of(first)));
      requireSuccess(result, "historical recovery");
      check(
          harness.store.readRecord(COMMITTED_NOT_FLUSHED).exists(), "target historical nggak ada");
      check(
          !harness.store.readRecord(COMMITTED_FLUSHED).exists(),
          "historical malah ikut commit setelah target");
    }

    try (RecoveryHarness reopened = RecoveryHarness.open(root, "historical")) {
      TransactionId third = new TransactionId(3);
      reopened.wal.begin(third, 3);
      LogSequenceNumber thirdLsn =
          reopened.wal.appendChange(
              new ChangeRecord(third, "items", COMMITTED_FLUSHED, RecordImage.absent(), image(3)));
      reopened.store.applyRecovery(new RecoveryMutation(COMMITTED_FLUSHED, image(3)), thirdLsn);
      reopened.wal.commitDurably(third);

      RecoveryResult branchResult =
          reopened.historical.recoverTo(
              new RecoveryTarget(Optional.of(Instant.now().plusSeconds(120)), Optional.empty()));
      requireSuccess(branchResult, "historical recovery on reopened branch");
      check(
          image(3).equals(reopened.store.readRecord(COMMITTED_FLUSHED)),
          "PITR pada branch baru kehilangan commit setelah restart");
      check(
          reopened.store.readRecord(COMMITTED_NOT_FLUSHED).exists(),
          "PITR branch baru kehilangan data snapshot sebelumnya");
    }

    try (RecoveryHarness reopenedAgain = RecoveryHarness.open(root, "historical")) {
      RecoveryResult repeatedResult =
          reopenedAgain.historical.recoverTo(
              new RecoveryTarget(Optional.of(Instant.now().plusSeconds(240)), Optional.empty()));
      requireSuccess(repeatedResult, "repeated historical recovery after restart");
      check(
          image(3).equals(reopenedAgain.store.readRecord(COMMITTED_FLUSHED)),
          "snapshot branch hasil PITR tidak bertahan setelah restart");
      check(
          reopenedAgain.store.readRecord(COMMITTED_NOT_FLUSHED).exists(),
          "snapshot branch kehilangan record dari recovery sebelumnya");
      return new TestResult(
          true, "historical: target commit, branch baru, dan PITR berulang setelah restart benar");
    }
  }

  static void seedCrashState(RecoveryHarness harness) {
    seedMultiTransaction(harness);
  }

  static void seedMultiTransaction(RecoveryHarness harness) {
    writeCommittedNotFlushed(harness);
    writeCommittedFlushed(harness);
    writeLoserFlushed(harness);
    writeAborted(harness);
  }

  private static void verifyMultiTransaction(RecoveryHarness reopened) {
    check(
        reopened.store.readRecord(COMMITTED_NOT_FLUSHED).exists(),
        "redo nggak balikin committed-not-flushed");
    check(reopened.store.readRecord(COMMITTED_FLUSHED).exists(), "committed-flushed hilang");
    check(!reopened.store.readRecord(LOSER_FLUSHED).exists(), "undo ninggalin loser");
    check(!reopened.store.readRecord(ABORTED).exists(), "record aborted masih ada");
    check(reopened.highWater().maxTx().value() >= 4, "high-water nggak balik ke CCM");
  }

  private static void writeCommittedNotFlushed(RecoveryHarness harness) {
    TransactionId tx = new TransactionId(1);
    harness.wal.begin(tx, 1);
    harness.wal.appendChange(
        new ChangeRecord(tx, "items", COMMITTED_NOT_FLUSHED, RecordImage.absent(), image(1)));
    harness.wal.commitDurably(tx);
    // log udah durable, page sengaja belum ditulis -> uji redo
  }

  private static void writeCommittedFlushed(RecoveryHarness harness) {
    TransactionId tx = new TransactionId(2);
    harness.wal.begin(tx, 2);
    LogSequenceNumber lsn =
        harness.wal.appendChange(
            new ChangeRecord(tx, "items", COMMITTED_FLUSHED, RecordImage.absent(), image(2)));
    harness.store.applyRecovery(new RecoveryMutation(COMMITTED_FLUSHED, image(2)), lsn);
    harness.wal.commitDurably(tx);
  }

  private static void writeLoserFlushed(RecoveryHarness harness) {
    TransactionId tx = new TransactionId(3);
    harness.wal.begin(tx, 3);
    LogSequenceNumber lsn =
        harness.wal.appendChange(
            new ChangeRecord(tx, "items", LOSER_FLUSHED, RecordImage.absent(), image(3)));
    harness.store.applyRecovery(new RecoveryMutation(LOSER_FLUSHED, image(3)), lsn);
    harness.wal.ensureDurable(lsn);
    // belum commit, tapi page udah keburu ditulis -> uji undo
  }

  private static void writeAborted(RecoveryHarness harness) {
    TransactionId tx = new TransactionId(4);
    harness.wal.begin(tx, 4);
    LogSequenceNumber lsn =
        harness.wal.appendChange(
            new ChangeRecord(tx, "items", ABORTED, RecordImage.absent(), image(4)));
    harness.store.applyRecovery(new RecoveryMutation(ABORTED, image(4)), lsn);
    harness.undo.rollbackCompletely(tx);
  }

  private Process startCrashChild(Path directory) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return new ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            RealRecoveryDriver.class.getName(),
            "crash-child",
            directory.toString())
        .redirectErrorStream(true)
        .start();
  }
}
