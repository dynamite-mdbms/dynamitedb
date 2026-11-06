package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UndoManagerTest {
  @TempDir Path directory;
  private static final RecoveryHighWater EMPTY =
      new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());
  private static final TransactionId TX = new TransactionId(1);
  private static final RecordId INSERT = new RecordId(1, 1, 1, 1);
  private static final RecordId UPDATE = new RecordId(1, 1, 2, 1);
  private static final RecordId DELETE = new RecordId(1, 1, 3, 1);
  private static final RecordId OTHER = new RecordId(1, 1, 4, 1);

  @Test
  void reversesAllChangesInOrderAndPreservesCommittedTransaction() {
    try (var log = LogManager.createNew(directory, UUID.randomUUID(), EMPTY)) {
      var store = new Store();
      log.begin(TX, 1);
      change(log, store, TX, INSERT, RecordImage.absent(), image(1));
      change(log, store, TX, UPDATE, image(2), image(3));
      change(log, store, TX, UPDATE, image(3), image(4));
      change(log, store, TX, DELETE, image(5), RecordImage.absent());
      var other = new TransactionId(2);
      log.begin(other, 2);
      change(log, store, other, OTHER, RecordImage.absent(), image(6));
      log.commit(other, new CommitClock());
      var undo = new UndoManager(log, store);
      undo.rollbackCompletely(TX);
      assertEquals(RecordImage.absent(), store.readRecord(INSERT));
      assertEquals(image(2), store.readRecord(UPDATE));
      assertEquals(image(5), store.readRecord(DELETE));
      assertEquals(image(6), store.readRecord(OTHER));
      assertEquals(List.of(DELETE, UPDATE, UPDATE, INSERT), store.applied);
      var records = records(log, TX);
      var clrs =
          records.stream()
              .filter(CompensationLogRecord.class::isInstance)
              .map(CompensationLogRecord.class::cast)
              .toList();
      assertEquals(4, clrs.size());
      assertEquals(log.appendedLsn(), log.durableLsn());
      long end = log.appendedLsn();
      undo.rollbackCompletely(TX);
      assertEquals(end, log.appendedLsn());
      assertEquals(4, store.applied.size());
    }
  }

  @Test
  void retryReappliesExistingClrAfterFailureAndReopen() {
    UUID lineage = UUID.randomUUID();
    var store = new Store();
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      log.begin(TX, 1);
      change(log, store, TX, UPDATE, image(1), image(2));
      store.fail = true;
      assertThrows(
          IllegalStateException.class, () -> new UndoManager(log, store).rollbackCompletely(TX));
      assertEquals(image(2), store.readRecord(UPDATE));
      assertEquals(
          1, records(log, TX).stream().filter(CompensationLogRecord.class::isInstance).count());
      assertEquals(
          0,
          records(log, TX).stream()
              .filter(
                  r ->
                      r instanceof TransactionEventLogRecord event
                          && event.event == TxEvent.ABORT_COMPLETE)
              .count());
      log.flush(new LogSequenceNumber(log.appendedLsn()));
    }
    store.fail = false;
    try (var log = LogManager.openExisting(directory, lineage, EMPTY)) {
      var clr = (CompensationLogRecord) records(log, TX).getFirst();
      new UndoManager(log, store).rollbackCompletely(TX);
      assertEquals(image(1), store.readRecord(UPDATE));
      assertEquals(clr.lsn, store.lsns.getFirst());
      assertEquals(
          1, records(log, TX).stream().filter(CompensationLogRecord.class::isInstance).count());
      assertEquals(
          TxEvent.ABORT_COMPLETE, ((TransactionEventLogRecord) records(log, TX).getFirst()).event);
      assertEquals(log.appendedLsn(), log.durableLsn());
    }
  }

  @Test
  void replaysMultipleClrsInWalOrderForSameRecord() {
    UUID lineage = UUID.randomUUID();
    var store = new Store();
    try (var log = LogManager.createNew(directory, lineage, EMPTY)) {
      log.begin(TX, 1);
      change(log, store, TX, UPDATE, image(1), image(2));
      change(log, store, TX, UPDATE, image(2), image(3));
      change(log, store, TX, UPDATE, image(3), image(4));
      store.failAfter = 1;
      assertThrows(
          IllegalStateException.class, () -> new UndoManager(log, store).rollbackCompletely(TX));
      assertEquals(image(3), store.readRecord(UPDATE));
      assertEquals(
          2, records(log, TX).stream().filter(CompensationLogRecord.class::isInstance).count());
      assertEquals(
          0,
          records(log, TX).stream()
              .filter(
                  r ->
                      r instanceof TransactionEventLogRecord event
                          && event.event == TxEvent.ABORT_COMPLETE)
              .count());
      log.flush(new LogSequenceNumber(log.appendedLsn()));
    }
    store.failAfter = Integer.MAX_VALUE;
    store.attempted.clear();
    try (var log = LogManager.openExisting(directory, lineage, EMPTY)) {
      var existing =
          records(log, TX).stream()
              .filter(CompensationLogRecord.class::isInstance)
              .map(r -> r.lsn)
              .toList();
      new UndoManager(log, store).rollbackCompletely(TX);
      assertEquals(List.of(existing.get(1), existing.get(0)), store.attempted.subList(0, 2));
      assertEquals(image(1), store.readRecord(UPDATE));
      assertEquals(
          3, records(log, TX).stream().filter(CompensationLogRecord.class::isInstance).count());
    }
  }

  @Test
  void emitClrReturnsTheLoggedRecordWithAllocatedLsnAndContinuation() {
    try (var log = LogManager.createNew(directory, UUID.randomUUID(), EMPTY)) {
      log.begin(TX, 1);
      log.appendChange(new ChangeRecord(TX, "items", UPDATE, image(1), image(2)));
      var original = (ChangeLogRecord) records(log, TX).getFirst();
      var clr = new UndoManager(log, new Store()).emitClr(original);
      assertEquals(clr, records(log, TX).getFirst());
      assertEquals(new LogSequenceNumber(3), clr.lsn);
      assertEquals(original.lsn, clr.compensates);
      assertEquals(original.prevLsn, clr.undoNext);
      assertEquals(new RecoveryMutation(UPDATE, image(1)), clr.mutation);
    }
  }

  @Test
  void rejectsUnknownAndCommittedTransactions() {
    try (var log = LogManager.createNew(directory, UUID.randomUUID(), EMPTY)) {
      var store = new Store();
      store.data.put(OTHER, image(6));
      var undo = new UndoManager(log, store);
      var dataBefore = Map.copyOf(store.data);
      long walBefore = log.appendedLsn();
      long durableBefore = log.durableLsn();
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(DbmsException.class, () -> undo.rollbackCompletely(TX)).getCode());
      assertEquals(dataBefore, store.data);
      assertEquals(List.of(), store.attempted);
      assertEquals(walBefore, log.appendedLsn());
      assertEquals(durableBefore, log.durableLsn());
      assertEquals(List.of(), records(log, TX));

      log.begin(TX, 1);
      change(log, store, TX, UPDATE, image(1), image(2));
      log.commit(TX, new CommitClock());
      dataBefore = Map.copyOf(store.data);
      walBefore = log.appendedLsn();
      durableBefore = log.durableLsn();
      var recordsBefore = records(log, TX);
      assertEquals(
          ErrorCode.INVALID_STATE,
          assertThrows(DbmsException.class, () -> undo.rollbackCompletely(TX)).getCode());
      assertEquals(dataBefore, store.data);
      assertEquals(List.of(), store.attempted);
      assertEquals(walBefore, log.appendedLsn());
      assertEquals(durableBefore, log.durableLsn());
      assertEquals(recordsBefore, records(log, TX));
    }
  }

  @Test
  void undoLosersOnlyRollsBackLosers() {
    try (var log = LogManager.createNew(directory, UUID.randomUUID(), EMPTY)) {
      var store = new Store();
      var other = new TransactionId(2);
      log.begin(TX, 1);
      change(log, store, TX, UPDATE, image(1), image(2));
      log.begin(other, 2);
      change(log, store, other, OTHER, RecordImage.absent(), image(3));
      log.commit(other, new CommitClock());
      new UndoManager(log, store)
          .undoLosers(
              new RecoveryState(
                  Set.of(other), Set.of(TX), new LogSequenceNumber(log.appendedLsn()), EMPTY));
      assertEquals(image(1), store.readRecord(UPDATE));
      assertEquals(image(3), store.readRecord(OTHER));
    }
  }

  private static RecordImage image(int value) {
    return new RecordImage(true, ImmutableBytes.copyOf(new byte[] {(byte) value}));
  }

  private static void change(
      LogManager log,
      Store store,
      TransactionId tx,
      RecordId rid,
      RecordImage before,
      RecordImage after) {
    log.appendChange(new ChangeRecord(tx, "items", rid, before, after));
    store.data.put(rid, after);
  }

  private static List<LogRecord> records(LogManager log, TransactionId tx) {
    var result = new ArrayList<LogRecord>();
    log.reverseByTx(tx).forEachRemaining(result::add);
    return result;
  }

  private static final class Store implements RecoveryPageStore {
    final Map<RecordId, RecordImage> data = new HashMap<>();
    final List<RecordId> applied = new ArrayList<>();
    final List<LogSequenceNumber> lsns = new ArrayList<>();
    final List<LogSequenceNumber> attempted = new ArrayList<>();
    boolean fail;
    int failAfter = Integer.MAX_VALUE;
    final Map<RecordId, LogSequenceNumber> lastLsn = new HashMap<>();

    public RecordImage readRecord(RecordId rid) {
      return data.getOrDefault(rid, RecordImage.absent());
    }

    public void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
      attempted.add(lsn);
      if (fail || applied.size() >= failAfter) throw new IllegalStateException("storage failure");
      if (lastLsn.containsKey(mutation.rid()) && lastLsn.get(mutation.rid()).value() >= lsn.value())
        return;
      lastLsn.put(mutation.rid(), lsn);
      data.put(mutation.rid(), mutation.targetImage());
      applied.add(mutation.rid());
      lsns.add(lsn);
    }

    public void rebuildIndexes() {}

    public void flushDirtyThrough(LogSequenceNumber lsn) {}
  }
}
