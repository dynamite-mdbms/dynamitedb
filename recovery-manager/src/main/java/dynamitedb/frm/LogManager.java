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

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class LogManager {
  private final Path root;
  private final UUID activeLineage;
  private final LogFile file;
  private final LogRecordCodec codec;
  private final MockRecoveryContext context;

  public LogManager(
      Path root,
      UUID activeLineage,
      LogFile file,
      LogRecordCodec codec,
      MockRecoveryContext context) {
    this.root = Objects.requireNonNull(root, "root");
    this.activeLineage = Objects.requireNonNull(activeLineage, "activeLineage");
    this.file = Objects.requireNonNull(file, "file");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.context = Objects.requireNonNull(context, "context");
  }

  public LogSequenceNumber append(LogRecord record) {
    Objects.requireNonNull(record, "record");
    context.invoked("LogManager.append");
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
    return context.highWater();
  }

  public Iterator<LogRecord> scanHistory(UUID lineage, LogSequenceNumber fromLsn) {
    Objects.requireNonNull(lineage, "lineage");
    Objects.requireNonNull(fromLsn, "fromLsn");
    context.invoked("LogManager.scanHistory");
    return List.<LogRecord>of(context.record()).iterator();
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
