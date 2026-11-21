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
import java.util.Arrays;
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
  private static final RecordId CRASH_UPDATED = new RecordId(3, 1, 1, 1);
  private static final RecordId CRASH_DELETED = new RecordId(3, 1, 2, 1);
  private static final RecordId CRASH_ABORTED = new RecordId(3, 1, 3, 1);

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
      boolean beginWritten =
          log.lines().stream().anyMatch(line -> line.contains("BEGIN") && line.contains("tx=T1"));
      boolean insertWritten =
          log.lines().stream().anyMatch(line -> line.contains("INSERT") && line.contains("tx=T1"));
      boolean commitWritten =
          log.lines().stream().anyMatch(line -> line.contains("COMMIT") && line.contains("tx=T1"));
      check(
          beginWritten && insertWritten && commitWritten,
          "log nggak memuat BEGIN/INSERT/COMMIT untuk T1: " + log.lines());
      String actual =
          "BEGIN="
              + beginWritten
              + ", INSERT="
              + insertWritten
              + ", COMMIT="
              + commitWritten
              + ", durable="
              + (harness.log.durableLsn() >= change.value());
      return DriverAssertions.result(
          "wal", "BEGIN=true, INSERT=true, COMMIT=true, durable=true", actual);
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
      String actual =
          "filterOnlyT1="
              + filtered.lines().stream().allMatch(line -> line.contains("tx=T1"))
              + ", readOnly="
              + (before == harness.log.appendedLsn());
      return DriverAssertions.result("log-inspection", "filterOnlyT1=true, readOnly=true", actual);
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
      var logLines = harness.wal.readLog(new LogFilter(Optional.of(tx), Optional.empty())).lines();
      String actual =
          "recordExists="
              + harness.store.readRecord(LOSER_FLUSHED).exists()
              + ", abortComplete="
              + logLines.stream().anyMatch(line -> line.contains("ABORT_COMPLETE"))
              + ", durable="
              + (harness.log.appendedLsn() == harness.log.durableLsn());
      return DriverAssertions.result(
          "rollback", "recordExists=false, abortComplete=true, durable=true", actual);
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
      boolean endMarker =
          harness.wal.readLog(new LogFilter(Optional.empty(), Optional.empty())).lines().stream()
              .anyMatch(line -> line.contains("CHECKPOINT_END"));
      check(result.checkpointLsn().value() > 0, "checkpoint nggak balikin LSN END");
      check(endMarker, "marker CHECKPOINT_END hilang");
      String actual =
          "endLsnPositive=" + (result.checkpointLsn().value() > 0) + ", endMarker=" + endMarker;
      return DriverAssertions.result("checkpoint", "endLsnPositive=true, endMarker=true", actual);
    }
  }

  TestResult multiTransaction() {
    try (RecoveryHarness harness = RecoveryHarness.fresh(root, "multi-transaction")) {
      seedMultiTransaction(harness);
    }
    try (RecoveryHarness reopened = RecoveryHarness.open(root, "multi-transaction")) {
      requireSuccess(reopened.initializer.openExisting(), "recovery multi-transaction");
      verifyMultiTransaction(reopened);
      return DriverAssertions.result(
          "multi-transaction",
          "records=[true,true,false,false], highWater=true",
          describeMulti(reopened));
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
      String actual =
          "updatedValueIs3="
              + image(3).equals(reopened.store.readRecord(MIXED_DML))
              + ", deletedRecordAbsent="
              + !reopened.store.readRecord(DELETED_RECORD).exists();
      return DriverAssertions.result(
          "mixed-dml", "updatedValueIs3=true, deletedRecordAbsent=true", actual);
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
      String actual =
          "preCheckpointValueRestored="
              + image(1).equals(reopened.store.readRecord(CHECKPOINT_BEFORE))
              + ", postCheckpointCommitRedone="
              + image(2).equals(reopened.store.readRecord(CHECKPOINT_AFTER));
      return DriverAssertions.result(
          "checkpoint-recovery",
          "preCheckpointValueRestored=true, postCheckpointCommitRedone=true",
          actual);
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
      return DriverAssertions.result(
          "restart-twice",
          "records=[true,true,false,false], highWater=true",
          describeMulti(second));
    }
  }

  TestResult crash() {
    return crashScenario("crash", false);
  }

  TestResult crashWithCheckpoint() {
    return crashScenario("crash-with-checkpoint", true);
  }

  private TestResult crashScenario(String scenario, boolean checkpointed) {
    Path directory = root.resolve(scenario);
    RecoveryHarness.deleteDirectory(directory);
    try {
      Files.createDirectories(directory);
      Process process = startCrashChild(directory, checkpointed);
      boolean finished = process.waitFor(30, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return new TestResult(false, scenario + " expected=child halts; actual=timeout");
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        return new TestResult(
            false, scenario + " expected=child halts with exit 0; actual=" + output.trim());
      }
    } catch (IOException failure) {
      return new TestResult(
          false, scenario + " expected=child starts; actual=" + failure.getMessage());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return new TestResult(false, scenario + " expected=child halts; actual=interrupted");
    }

    try (RecoveryHarness reopened = RecoveryHarness.open(root, scenario)) {
      requireSuccess(reopened.initializer.openExisting(), scenario + " recovery");
      RecordImage expectedUpdated = image(checkpointed ? 25 : 20);
      RecordImage actualUpdated = reopened.store.readRecord(CRASH_UPDATED);
      RecordImage actualDeleted = reopened.store.readRecord(CRASH_DELETED);
      RecordImage actualAborted = reopened.store.readRecord(CRASH_ABORTED);
      check(expectedUpdated.equals(actualUpdated), "nilai record selepas redo/undo keliru");
      check(!actualDeleted.exists(), "DELETE committed muncul kembali");
      check(!actualAborted.exists(), "record transaksi abort tertinggal");
      check(reopened.highWater().maxTx().value() >= (checkpointed ? 7 : 6), "high-water salah");
      String expected = "updated=" + describe(expectedUpdated) + ", deleted=absent, aborted=absent";
      String actual =
          "updated="
              + describe(actualUpdated)
              + ", deleted="
              + describe(actualDeleted)
              + ", aborted="
              + describe(actualAborted);
      return DriverAssertions.result(scenario, expected, actual);
    }
  }

  TestResult historical() {
    boolean targetRestored;
    boolean branchRestored;
    boolean repeatedRestoreSurvivedRestart;
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
      targetRestored =
          harness.store.readRecord(COMMITTED_NOT_FLUSHED).exists()
              && !harness.store.readRecord(COMMITTED_FLUSHED).exists();
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
      branchRestored =
          image(3).equals(reopened.store.readRecord(COMMITTED_FLUSHED))
              && reopened.store.readRecord(COMMITTED_NOT_FLUSHED).exists();
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
      repeatedRestoreSurvivedRestart =
          image(3).equals(reopenedAgain.store.readRecord(COMMITTED_FLUSHED))
              && reopenedAgain.store.readRecord(COMMITTED_NOT_FLUSHED).exists();
      String expected = "target=true, branch=true, repeatedAfterRestart=true";
      String actual =
          "target="
              + targetRestored
              + ", branch="
              + branchRestored
              + ", repeatedAfterRestart="
              + repeatedRestoreSurvivedRestart;
      return DriverAssertions.result("historical", expected, actual);
    }
  }

  static void seedCrashState(RecoveryHarness harness, boolean checkpointed) {
    TransactionId insert = new TransactionId(1);
    harness.wal.begin(insert, 1);
    harness.wal.appendChange(
        new ChangeRecord(insert, "items", CRASH_UPDATED, RecordImage.absent(), image(10)));
    harness.wal.commitDurably(insert);
    // Page sengaja belum ditulis: recovery harus mengulang INSERT committed.

    TransactionId update = new TransactionId(2);
    harness.wal.begin(update, 2);
    LogSequenceNumber updateLsn =
        harness.wal.appendChange(
            new ChangeRecord(update, "items", CRASH_UPDATED, image(10), image(20)));
    harness.store.applyRecovery(new RecoveryMutation(CRASH_UPDATED, image(20)), updateLsn);
    harness.wal.commitDurably(update);

    TransactionId addDeleted = new TransactionId(3);
    harness.wal.begin(addDeleted, 3);
    LogSequenceNumber addDeletedLsn =
        harness.wal.appendChange(
            new ChangeRecord(addDeleted, "items", CRASH_DELETED, RecordImage.absent(), image(30)));
    harness.store.applyRecovery(new RecoveryMutation(CRASH_DELETED, image(30)), addDeletedLsn);
    harness.wal.commitDurably(addDeleted);

    TransactionId delete = new TransactionId(4);
    harness.wal.begin(delete, 4);
    LogSequenceNumber deleteLsn =
        harness.wal.appendChange(
            new ChangeRecord(delete, "items", CRASH_DELETED, image(30), RecordImage.absent()));
    harness.store.applyRecovery(
        new RecoveryMutation(CRASH_DELETED, RecordImage.absent()), deleteLsn);
    harness.wal.commitDurably(delete);

    TransactionId aborted = new TransactionId(5);
    harness.wal.begin(aborted, 5);
    LogSequenceNumber abortedLsn =
        harness.wal.appendChange(
            new ChangeRecord(aborted, "items", CRASH_ABORTED, RecordImage.absent(), image(40)));
    harness.store.applyRecovery(new RecoveryMutation(CRASH_ABORTED, image(40)), abortedLsn);
    harness.undo.rollbackCompletely(aborted);

    if (checkpointed) {
      CheckpointResult checkpoint = harness.checkpoint.checkpoint();
      check(checkpoint.checkpointLsn().value() > 0, "checkpoint sebelum crash gagal");

      TransactionId committedAfterCheckpoint = new TransactionId(6);
      harness.wal.begin(committedAfterCheckpoint, 6);
      harness.wal.appendChange(
          new ChangeRecord(committedAfterCheckpoint, "items", CRASH_UPDATED, image(20), image(25)));
      harness.wal.commitDurably(committedAfterCheckpoint);

      TransactionId active = new TransactionId(7);
      harness.wal.begin(active, 7);
      LogSequenceNumber activeLsn =
          harness.wal.appendChange(
              new ChangeRecord(active, "items", CRASH_UPDATED, image(25), image(99)));
      harness.store.applyRecovery(new RecoveryMutation(CRASH_UPDATED, image(99)), activeLsn);
      harness.wal.ensureDurable(activeLsn);
    } else {
      TransactionId active = new TransactionId(6);
      harness.wal.begin(active, 6);
      LogSequenceNumber activeLsn =
          harness.wal.appendChange(
              new ChangeRecord(active, "items", CRASH_UPDATED, image(20), image(99)));
      harness.store.applyRecovery(new RecoveryMutation(CRASH_UPDATED, image(99)), activeLsn);
      harness.wal.ensureDurable(activeLsn);
    }
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

  private static String describeMulti(RecoveryHarness harness) {
    return "records=["
        + harness.store.readRecord(COMMITTED_NOT_FLUSHED).exists()
        + ","
        + harness.store.readRecord(COMMITTED_FLUSHED).exists()
        + ","
        + harness.store.readRecord(LOSER_FLUSHED).exists()
        + ","
        + harness.store.readRecord(ABORTED).exists()
        + "], highWater="
        + (harness.highWater().maxTx().value() >= 4);
  }

  private static String describe(RecordImage image) {
    return image.exists() ? Arrays.toString(image.encoded().copy()) : "absent";
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

  private Process startCrashChild(Path directory, boolean checkpointed) throws IOException {
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var command =
        new java.util.ArrayList<>(
            java.util.List.of(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                RealRecoveryDriver.class.getName(),
                "crash-child",
                directory.toString()));
    if (checkpointed) command.add("with-checkpoint");
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }
}
