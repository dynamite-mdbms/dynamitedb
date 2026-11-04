package dynamitedb.frm;

import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.LogView;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Milestone 1 mock only. Fixture responses and call recording without durable DBMS behavior. */
public final class LogManager {
  private final Path root;
  private final UUID activeLineage;
  private final LogFile file;
  private final LogRecordCodec codec;
  private final MockRecoveryContext context;
  private final List<LogRecord> history;
  private final RecoveryHighWater highWater;
  private long nextMockLsn;

  public LogManager(
      Path root,
      UUID activeLineage,
      LogFile file,
      LogRecordCodec codec,
      MockRecoveryContext context) {
    this(root, activeLineage, file, codec, context, List.of(context.record()), context.highWater());
  }

  LogManager(
      Path root,
      UUID activeLineage,
      LogFile file,
      LogRecordCodec codec,
      MockRecoveryContext context,
      List<LogRecord> history,
      RecoveryHighWater highWater) {
    this.root = Objects.requireNonNull(root, "root");
    this.activeLineage = Objects.requireNonNull(activeLineage, "activeLineage");
    this.file = Objects.requireNonNull(file, "file");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.context = Objects.requireNonNull(context, "context");
    this.history = List.copyOf(Objects.requireNonNull(history, "history"));
    this.highWater = Objects.requireNonNull(highWater, "highWater");
    this.nextMockLsn = context.lsn().value() + 1;
  }

  UUID activeLineage() {
    return activeLineage;
  }

  synchronized LogSequenceNumber nextCheckpointLsn() {
    // Mock LSN allocation only. FR2 must replace it with shared WAL allocation.
    return new LogSequenceNumber(nextMockLsn++);
  }

  public LogSequenceNumber append(LogRecord record) {
    Objects.requireNonNull(record, "record");
    context.invoked("LogManager.append");
    if (record instanceof CheckpointLogRecord checkpoint) {
      context.checkpointRecord(checkpoint);
      return checkpoint.lsn;
    }
    return context.lsn();
  }

  public void flush(LogSequenceNumber lsn) {
    Objects.requireNonNull(lsn, "lsn");
    context.invoked("LogManager.flush");
  }

  public void prepareLineage(UUID id, SealedGeneration base, RecoveryHighWater highWater) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(highWater, "highWater");
    context.invoked("LogManager.prepareLineage");
  }

  public void activate(GenerationManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    context.invoked("LogManager.activate");
  }

  public RecoveryHighWater readHighWater() {
    context.invoked("LogManager.readHighWater");
    return highWater;
  }

  public Iterator<LogRecord> scanHistory(UUID lineage, LogSequenceNumber fromLsn) {
    Objects.requireNonNull(lineage, "lineage");
    Objects.requireNonNull(fromLsn, "fromLsn");
    context.invoked("LogManager.scanHistory");
    return history.stream()
        .filter(record -> record.walLineage.equals(lineage))
        .filter(record -> record.lsn.value() >= fromLsn.value())
        .iterator();
  }

  public Iterator<LogRecord> scanFrom(LogSequenceNumber lsn) {
    Objects.requireNonNull(lsn, "lsn");
    context.invoked("LogManager.scanFrom");
    return List.<LogRecord>of(context.record()).iterator();
  }

  public Iterator<LogRecord> reverseByTx(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    context.invoked("LogManager.reverseByTx");
    return List.<LogRecord>of(context.record()).iterator();
  }

  public LogView read(LogFilter filter) {
    Objects.requireNonNull(filter, "filter");
    context.invoked("LogManager.read");
    return context.logView();
  }
}
