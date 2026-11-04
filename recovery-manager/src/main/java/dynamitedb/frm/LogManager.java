package dynamitedb.frm;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.LogView;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Serializes WAL append and owns its transaction chains, LSNs, and durable prefix. */
public final class LogManager implements AutoCloseable {
  private enum TxState {
    ACTIVE,
    COMMITTED,
    ABORTED
  }

  private final Path root;
  private UUID lineage;
  private LogFile file;
  private final LogRecordCodec codec;
  private final MockRecoveryContext mock;
  private RecoveryHighWater seed;
  private final Map<UUID, Prepared> prepared = new HashMap<>();
  private final Map<UUID, Long> knownHistory = new HashMap<>();
  private final List<LogRecord> records = new ArrayList<>();
  private final Map<TransactionId, List<LogRecord>> byTx = new HashMap<>();
  private final Map<TransactionId, TxState> states = new HashMap<>();
  private final Map<Long, LogRecord> byLsn = new HashMap<>();
  private final Set<Long> compensated = new HashSet<>();
  private final Set<TransactionId> rollingBack = new HashSet<>();
  private final Map<UUID, CheckpointLogRecord> checkpoints = new HashMap<>();
  private long lastLsn;
  private long durableLsn;
  private long maxTx;
  private long maxTimestamp;
  private Instant maxCommitTime;

  public LogManager(
      Path root, UUID lineage, LogFile file, LogRecordCodec codec, MockRecoveryContext context) {
    this.root = Objects.requireNonNull(root);
    this.lineage = Objects.requireNonNull(lineage);
    this.file = Objects.requireNonNull(file);
    this.codec = Objects.requireNonNull(codec);
    this.mock = Objects.requireNonNull(context);
    this.seed = context.highWater();
  }

  private LogManager(
      Path root, UUID lineage, LogFile file, LogRecordCodec codec, RecoveryHighWater seed) {
    this.root = Objects.requireNonNull(root);
    this.lineage = Objects.requireNonNull(lineage);
    this.file = Objects.requireNonNull(file);
    this.codec = Objects.requireNonNull(codec);
    this.mock = null;
    this.seed = Objects.requireNonNull(seed);
    this.lastLsn = seed.lsn().value();
    this.durableLsn = seed.lsn().value();
    this.maxTx = seed.maxTx().value();
    this.maxTimestamp = seed.maxTimestamp();
    this.maxCommitTime = seed.maxCommitTime().orElse(null);
    if (lastLsn < 0 || maxTx < 0 || maxTimestamp < 0 || file.floor() != lastLsn) {
      throw new IllegalArgumentException("Invalid WAL high-water baseline");
    }
  }

  public static LogManager createNew(Path root, UUID lineage, RecoveryHighWater seed) {
    var file = LogFile.createNew(root, lineage, seed.lsn().value());
    try {
      return new LogManager(root, lineage, file, new LogRecordCodec(), seed);
    } catch (RuntimeException failure) {
      file.close();
      throw failure;
    }
  }

  public static LogManager openExisting(Path root, UUID lineage, RecoveryHighWater seed) {
    var file = LogFile.openExisting(root, lineage, seed.lsn().value());
    try {
      var manager = new LogManager(root, lineage, file, new LogRecordCodec(), seed);
      for (var frames = file.readRecoverableFrames(); frames.hasNext(); ) {
        manager.accept(manager.codec.decode(frames.next()), true);
      }
      file.repairIncompleteTail();
      manager.durableLsn = manager.lastLsn;
      return manager;
    } catch (RuntimeException failure) {
      file.close();
      throw failure;
    }
  }

  public synchronized UUID activeLineage() {
    return lineage;
  }

  public synchronized long appendedLsn() {
    return lastLsn;
  }

  public synchronized long durableLsn() {
    return durableLsn;
  }

  /** Direct append for FRM collaborators; the record must use the next exact LSN and chain. */
  public synchronized LogSequenceNumber append(LogRecord record) {
    Objects.requireNonNull(record, "record");
    if (mock != null) {
      mock.invoked("LogManager.append");
      return mock.lsn();
    }
    accept(record, false);
    if (record instanceof TransactionEventLogRecord event
        && (event.event == TxEvent.COMMIT || event.event == TxEvent.ABORT_COMPLETE)) {
      flush(record.lsn);
    }
    return record.lsn;
  }

  public synchronized LogSequenceNumber begin(TransactionId tx, long timestamp) {
    requireReal();
    Objects.requireNonNull(tx, "tx");
    if (tx.value() <= seed.maxTx().value()
        || timestamp <= seed.maxTimestamp()
        || states.containsKey(tx)) throw invalid("Invalid or reused transaction");
    return append(
        new TransactionEventLogRecord(
            next(),
            tx,
            Optional.empty(),
            lineage,
            TxEvent.BEGIN,
            Optional.of(timestamp),
            Optional.empty()));
  }

  public synchronized LogSequenceNumber appendChange(ChangeRecord change) {
    requireReal();
    Objects.requireNonNull(change, "change");
    requireActive(change.tx());
    return append(new ChangeLogRecord(next(), previous(change.tx()), lineage, change));
  }

  public synchronized LogSequenceNumber commit(TransactionId tx, CommitClock clock) {
    requireReal();
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(clock, "clock");
    if (states.get(tx) == TxState.COMMITTED) {
      LogSequenceNumber recorded = byTx.get(tx).getLast().lsn;
      flush(recorded);
      return recorded;
    }
    requireActive(tx);
    if (maxCommitTime != null) clock.seed(maxCommitTime);
    Instant time = clock.assign(Instant.now());
    LogSequenceNumber lsn =
        append(
            new TransactionEventLogRecord(
                next(),
                tx,
                previous(tx),
                lineage,
                TxEvent.COMMIT,
                Optional.empty(),
                Optional.of(time)));
    flush(lsn);
    return lsn;
  }

  public synchronized LogSequenceNumber appendCompensation(
      TransactionId tx,
      LogSequenceNumber compensated,
      Optional<LogSequenceNumber> undoNext,
      RecoveryMutation mutation) {
    requireReal();
    requireActive(Objects.requireNonNull(tx));
    Objects.requireNonNull(compensated);
    Objects.requireNonNull(undoNext);
    Objects.requireNonNull(mutation);
    return append(
        new CompensationLogRecord(
            next(), tx, previous(tx), lineage, compensated, undoNext, mutation));
  }

  public synchronized LogSequenceNumber finishAbort(TransactionId tx) {
    requireReal();
    requireActive(Objects.requireNonNull(tx));
    LogSequenceNumber result =
        append(
            new TransactionEventLogRecord(
                next(),
                tx,
                previous(tx),
                lineage,
                TxEvent.ABORT_COMPLETE,
                Optional.empty(),
                Optional.empty()));
    flush(result);
    return result;
  }

  public synchronized LogSequenceNumber appendCheckpoint(
      UUID id, CheckpointEvent event, LogSequenceNumber through) {
    requireReal();
    Objects.requireNonNull(id);
    Objects.requireNonNull(event);
    Objects.requireNonNull(through);
    return append(new CheckpointLogRecord(next(), lineage, id, event, through));
  }

  public synchronized void flush(LogSequenceNumber lsn) {
    Objects.requireNonNull(lsn, "lsn");
    if (mock != null) {
      mock.invoked("LogManager.flush");
      return;
    }
    long value = lsn.value();
    if (value < file.floor() || value > lastLsn) throw invalid("LSN outside active WAL prefix");
    if (value <= durableLsn) return;
    file.sync();
    durableLsn = lastLsn;
  }

  private void accept(LogRecord record, boolean replay) {
    if (!record.walLineage.equals(lineage)) throw invalidOrCorrupt(replay, "Wrong WAL lineage");
    if (lastLsn == Long.MAX_VALUE || record.lsn.value() != lastLsn + 1) {
      throw invalidOrCorrupt(replay, "WAL LSN must be the next value");
    }
    if (record instanceof CheckpointLogRecord checkpoint) {
      if (record.tx.isPresent() || record.prevLsn.isPresent()) {
        throw invalidOrCorrupt(replay, "Checkpoint has transaction metadata");
      }
      if (checkpoint.throughLsn.value() < file.floor() || checkpoint.throughLsn.value() > lastLsn) {
        throw invalidOrCorrupt(replay, "Invalid checkpoint WAL boundary");
      }
      CheckpointLogRecord begin = checkpoints.get(checkpoint.checkpointId);
      if (checkpoint.event == CheckpointEvent.BEGIN && begin != null) {
        throw invalidOrCorrupt(replay, "Duplicate checkpoint BEGIN");
      }
      if (checkpoint.event == CheckpointEvent.END
          && (begin == null || !begin.throughLsn.equals(checkpoint.throughLsn))) {
        throw invalidOrCorrupt(replay, "Checkpoint END has no matching BEGIN");
      }
    } else {
      TransactionId tx = record.tx.orElseThrow();
      if (tx.value() <= 0 || !record.prevLsn.equals(previous(tx))) {
        throw invalidOrCorrupt(replay, "Invalid transaction chain");
      }
      TxState state = states.get(tx);
      if (record instanceof TransactionEventLogRecord event && event.event == TxEvent.BEGIN) {
        if (state != null
            || event.prevLsn.isPresent()
            || tx.value() <= seed.maxTx().value()
            || event.beginTimestamp.orElseThrow() <= seed.maxTimestamp())
          throw invalidOrCorrupt(replay, "Duplicate BEGIN");
      } else if (state != TxState.ACTIVE) {
        throw invalidOrCorrupt(replay, "Transaction is not active");
      }
      if (record instanceof CompensationLogRecord clr) {
        LogRecord original = byLsn.get(clr.compensates.value());
        if (!(original instanceof ChangeLogRecord change)
            || !original.tx.equals(record.tx)
            || !change.change.rid().equals(clr.mutation.rid())
            || !change.change.before().equals(clr.mutation.targetImage())
            || !change.prevLsn.equals(clr.undoNext)
            || compensated.contains(clr.compensates.value())) {
          throw invalidOrCorrupt(replay, "Invalid or repeated compensation record");
        }
      }
      if (record instanceof ChangeLogRecord && rollingBack.contains(tx)) {
        throw invalidOrCorrupt(replay, "Change after rollback began");
      }
      if (record instanceof TransactionEventLogRecord event
          && event.event == TxEvent.COMMIT
          && ((maxCommitTime != null && !event.commitTime.orElseThrow().isAfter(maxCommitTime))
              || rollingBack.contains(tx))) {
        throw invalidOrCorrupt(replay, "Invalid commit time or rollback state");
      }
      if (record instanceof TransactionEventLogRecord event
          && event.event == TxEvent.ABORT_COMPLETE
          && byTx.getOrDefault(tx, List.of()).stream()
              .filter(r -> r instanceof ChangeLogRecord)
              .anyMatch(r -> !compensated.contains(r.lsn.value()))) {
        throw invalidOrCorrupt(replay, "ABORT completion before all changes were compensated");
      }
    }
    if (!replay) file.appendFrame(codec.encode(record));
    records.add(record);
    byLsn.put(record.lsn.value(), record);
    if (record instanceof CompensationLogRecord clr) {
      compensated.add(clr.compensates.value());
      rollingBack.add(clr.tx.orElseThrow());
    }
    if (record instanceof CheckpointLogRecord checkpoint) {
      if (checkpoint.event == CheckpointEvent.BEGIN)
        checkpoints.put(checkpoint.checkpointId, checkpoint);
      else checkpoints.remove(checkpoint.checkpointId);
    }
    record.tx.ifPresent(tx -> byTx.computeIfAbsent(tx, ignored -> new ArrayList<>()).add(record));
    if (record instanceof TransactionEventLogRecord event) {
      TransactionId tx = event.tx.orElseThrow();
      switch (event.event) {
        case BEGIN -> {
          states.put(tx, TxState.ACTIVE);
          maxTimestamp = Math.max(maxTimestamp, event.beginTimestamp.orElseThrow());
        }
        case COMMIT -> {
          states.put(tx, TxState.COMMITTED);
          Instant time = event.commitTime.orElseThrow();
          if (maxCommitTime == null || time.isAfter(maxCommitTime)) maxCommitTime = time;
        }
        case ABORT_COMPLETE -> states.put(tx, TxState.ABORTED);
      }
    }
    record.tx.ifPresent(tx -> maxTx = Math.max(maxTx, tx.value()));
    lastLsn = record.lsn.value();
  }

  public synchronized RecoveryHighWater readHighWater() {
    if (mock != null) {
      mock.invoked("LogManager.readHighWater");
      return mock.highWater();
    }
    return new RecoveryHighWater(
        new LogSequenceNumber(lastLsn),
        new TransactionId(maxTx),
        maxTimestamp,
        Optional.ofNullable(maxCommitTime));
  }

  public synchronized Iterator<LogRecord> scanHistory(UUID requested, LogSequenceNumber fromLsn) {
    Objects.requireNonNull(requested, "lineage");
    Objects.requireNonNull(fromLsn, "fromLsn");
    if (mock != null) {
      mock.invoked("LogManager.scanHistory");
      return List.<LogRecord>of(mock.record()).iterator();
    }
    if (requested.equals(lineage)) return scanFrom(fromLsn);
    Long floor = knownHistory.get(requested);
    if (floor == null) throw invalid("Unknown historical lineage");
    try (var history = LogFile.openHistory(root, requested, floor)) {
      var result = new ArrayList<LogRecord>();
      long expected = floor;
      for (var frames = history.readFrames(); frames.hasNext(); ) {
        LogRecord record = codec.decode(frames.next());
        if (!record.walLineage.equals(requested))
          throw new DbmsException(ErrorCode.CORRUPT_LOG, "Historical lineage mismatch");
        if (expected == Long.MAX_VALUE || record.lsn.value() != ++expected)
          throw new DbmsException(ErrorCode.CORRUPT_LOG, "Historical LSN gap");
        if (record.lsn.value() >= fromLsn.value()) result.add(record);
      }
      return result.iterator();
    }
  }

  /** Published history must be registered by the catalog after startup. */
  public synchronized void registerHistory(UUID id, long floor) {
    requireReal();
    Objects.requireNonNull(id);
    if (id.equals(lineage) || floor < 0) throw invalid("Invalid historical lineage");
    knownHistory.put(id, floor);
  }

  public synchronized Iterator<LogRecord> scanFrom(LogSequenceNumber lsn) {
    Objects.requireNonNull(lsn, "lsn");
    if (mock != null) {
      mock.invoked("LogManager.scanFrom");
      return List.<LogRecord>of(mock.record()).iterator();
    }
    return records.stream().filter(r -> r.lsn.value() >= lsn.value()).toList().iterator();
  }

  public synchronized Iterator<LogRecord> reverseByTx(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    if (mock != null) {
      mock.invoked("LogManager.reverseByTx");
      return List.<LogRecord>of(mock.record()).iterator();
    }
    var result = new ArrayList<>(byTx.getOrDefault(tx, List.of()));
    java.util.Collections.reverse(result);
    return result.iterator();
  }

  public synchronized LogView read(LogFilter filter) {
    Objects.requireNonNull(filter, "filter");
    if (mock != null) {
      mock.invoked("LogManager.read");
      return mock.logView();
    }
    return new LogView(
        records.stream()
            .filter(
                r ->
                    filter.fromLsn().isEmpty()
                        || r.lsn.value() >= filter.fromLsn().orElseThrow().value())
            .filter(r -> filter.tx().isEmpty() || r.tx.equals(filter.tx()))
            .map(LogManager::describe)
            .toList());
  }

  public synchronized void prepareLineage(
      UUID id, SealedGeneration base, RecoveryHighWater highWater) {
    Objects.requireNonNull(id);
    Objects.requireNonNull(base);
    Objects.requireNonNull(highWater);
    if (mock != null) {
      mock.invoked("LogManager.prepareLineage");
      return;
    }
    if (states.values().stream().anyMatch(s -> s == TxState.ACTIVE))
      throw invalid("Active transactions block WAL preparation");
    if (id.equals(lineage)
        || prepared.containsKey(id)
        || highWater.lsn().value() < lastLsn
        || highWater.lsn().value() < base.boundary().inclusiveLsn().value()
        || highWater.maxTx().value() < maxTx
        || highWater.maxTimestamp() < maxTimestamp
        || (maxCommitTime != null
            && highWater.maxCommitTime().filter(t -> !t.isBefore(maxCommitTime)).isEmpty())
        || (base.boundary().commitTime().isPresent()
            && highWater
                .maxCommitTime()
                .filter(t -> !t.isBefore(base.boundary().commitTime().orElseThrow()))
                .isEmpty())) {
      throw invalid("Invalid lineage preparation");
    }
    flush(new LogSequenceNumber(lastLsn));
    var created = LogFile.createNew(root, id, highWater.lsn().value());
    created.close();
    prepared.put(id, new Prepared(base, highWater));
  }

  public synchronized void activate(GenerationManifest manifest) {
    Objects.requireNonNull(manifest);
    if (mock != null) {
      mock.invoked("LogManager.activate");
      return;
    }
    if (states.values().stream().anyMatch(s -> s == TxState.ACTIVE))
      throw invalid("Active transactions block WAL activation");
    Prepared choice = prepared.get(manifest.walLineage());
    if (choice == null
        || !choice.base.id().equals(manifest.activeId())
        || !choice.base.boundary().inclusiveLsn().equals(manifest.boundaryLsn())
        || !choice.highWater.equals(manifest.highWater())
        || lastLsn > manifest.highWater().lsn().value()) {
      throw invalid("Manifest does not match prepared lineage");
    }
    LogManager replacement = openExisting(root, manifest.walLineage(), manifest.highWater());
    if (replacement.lastLsn != manifest.highWater().lsn().value()) {
      replacement.close();
      throw invalid("Prepared WAL must be empty before activation");
    }
    try {
      flush(new LogSequenceNumber(lastLsn));
    } catch (RuntimeException failure) {
      replacement.close();
      throw failure;
    }
    knownHistory.put(lineage, file.floor());
    try {
      file.close();
    } catch (RuntimeException failure) {
      replacement.close();
      throw failure;
    }
    file = replacement.file;
    lineage = replacement.lineage;
    seed = replacement.seed;
    records.clear();
    records.addAll(replacement.records);
    byTx.clear();
    byTx.putAll(replacement.byTx);
    states.clear();
    states.putAll(replacement.states);
    byLsn.clear();
    byLsn.putAll(replacement.byLsn);
    compensated.clear();
    compensated.addAll(replacement.compensated);
    rollingBack.clear();
    rollingBack.addAll(replacement.rollingBack);
    checkpoints.clear();
    checkpoints.putAll(replacement.checkpoints);
    lastLsn = replacement.lastLsn;
    durableLsn = replacement.durableLsn;
    maxTx = replacement.maxTx;
    maxTimestamp = replacement.maxTimestamp;
    maxCommitTime = replacement.maxCommitTime;
    prepared.remove(manifest.walLineage());
  }

  private Optional<LogSequenceNumber> previous(TransactionId tx) {
    var entries = byTx.get(tx);
    return entries == null || entries.isEmpty()
        ? Optional.empty()
        : Optional.of(entries.getLast().lsn);
  }

  private LogSequenceNumber next() {
    if (lastLsn == Long.MAX_VALUE) throw invalid("WAL LSN overflow");
    return new LogSequenceNumber(lastLsn + 1);
  }

  private void requireActive(TransactionId tx) {
    if (states.get(tx) != TxState.ACTIVE) throw invalid("Transaction is not active: " + tx);
  }

  private void requireReal() {
    if (mock != null) throw new IllegalStateException("Real WAL method called on a mock manager");
  }

  @Override
  public synchronized void close() {
    if (mock == null) file.close();
  }

  private static String describe(LogRecord r) {
    String prefix = "LSN=" + r.lsn.value() + " tx=" + r.tx.map(Object::toString).orElse("-") + " ";
    if (r instanceof TransactionEventLogRecord event) {
      return prefix
          + event.event
          + event.beginTimestamp.map(v -> " ts=" + v).orElse("")
          + event.commitTime.map(v -> " commit=" + v).orElse("");
    }
    if (r instanceof ChangeLogRecord change) {
      String kind =
          !change.change.before().exists()
              ? "INSERT"
              : !change.change.after().exists() ? "DELETE" : "UPDATE";
      return prefix
          + kind
          + " table="
          + change.change.table()
          + " rid="
          + change.change.rid()
          + " before="
          + image(change.change.before())
          + " after="
          + image(change.change.after());
    }
    if (r instanceof CompensationLogRecord clr) {
      return prefix
          + "CLR compensates="
          + clr.compensates.value()
          + " undoNext="
          + clr.undoNext.map(v -> Long.toString(v.value())).orElse("-")
          + " rid="
          + clr.mutation.rid()
          + " target="
          + image(clr.mutation.targetImage());
    }
    if (r instanceof CheckpointLogRecord checkpoint) {
      return prefix
          + "CHECKPOINT_"
          + checkpoint.event
          + " id="
          + checkpoint.checkpointId
          + " through="
          + checkpoint.throughLsn.value();
    }
    throw new IllegalArgumentException("Unknown record kind");
  }

  private static String image(dynamitedb.contracts.storage.RecordImage value) {
    return value.exists() ? java.util.HexFormat.of().formatHex(value.encoded().copy()) : "ABSENT";
  }

  private static DbmsException invalid(String message) {
    return new DbmsException(ErrorCode.INVALID_STATE, message);
  }

  private static DbmsException invalidOrCorrupt(boolean replay, String message) {
    return new DbmsException(replay ? ErrorCode.CORRUPT_LOG : ErrorCode.INVALID_STATE, message);
  }

  private record Prepared(SealedGeneration base, RecoveryHighWater highWater) {}
}
