package dynamitedb.frm;

import dynamitedb.contracts.api.LogInspection;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.api.WalDurability;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.LogView;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;
import java.util.UUID;

/** Public WAL ports backed by one log manager; the Milestone 1 mock remains injectable. */
public final class WalService implements TransactionWal, WalDurability, LogInspection {
  private final CommitClock clock;
  private final LogManager log;
  private final MockRecoveryContext context;

  public WalService(CommitClock clock, LogManager log, MockRecoveryContext context) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.log = Objects.requireNonNull(log, "log");
    this.context = Objects.requireNonNull(context, "context");
  }

  public WalService(CommitClock clock, LogManager log) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.log = Objects.requireNonNull(log, "log");
    this.context = null;
    log.readHighWater().maxCommitTime().ifPresent(clock::seed);
  }

  public void begin(TransactionId tx, long timestamp) {
    Objects.requireNonNull(tx, "tx");
    if (context != null) context.invoked("WalService.begin");
    else log.begin(tx, timestamp);
  }

  public LogSequenceNumber appendChange(ChangeRecord change) {
    Objects.requireNonNull(change, "change");
    if (context != null) {
      context.invoked("WalService.appendChange");
      return context.lsn();
    }
    return log.appendChange(change);
  }

  public void commitDurably(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    if (context != null) context.invoked("WalService.commitDurably");
    else log.commit(tx, clock);
  }

  public void ensureDurable(LogSequenceNumber lsn) {
    Objects.requireNonNull(lsn, "lsn");
    if (context != null) context.invoked("WalService.ensureDurable");
    log.flush(lsn);
  }

  public LogView readLog(LogFilter filter) {
    Objects.requireNonNull(filter, "filter");
    if (context != null) context.invoked("WalService.readLog");
    return log.read(filter);
  }

  public void prepareLineage(UUID id, SealedGeneration base, RecoveryHighWater highWater) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(highWater, "highWater");
    if (context != null) context.invoked("WalService.prepareLineage");
    log.prepareLineage(id, base, highWater);
  }

  public void activate(GenerationManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    if (context != null) context.invoked("WalService.activate");
    log.activate(manifest);
    if (context == null) log.readHighWater().maxCommitTime().ifPresent(clock::seed);
  }
}
