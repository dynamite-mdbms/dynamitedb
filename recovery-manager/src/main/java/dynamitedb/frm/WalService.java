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

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class WalService implements TransactionWal, WalDurability, LogInspection {
  private final CommitClock clock;
  private final LogManager log;
  private final MockRecoveryContext context;

  public WalService(CommitClock clock, LogManager log, MockRecoveryContext context) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.log = Objects.requireNonNull(log, "log");
    this.context = Objects.requireNonNull(context, "context");
  }

  public void begin(TransactionId tx, long timestamp) {
    Objects.requireNonNull(tx, "tx");
    context.invoked("WalService.begin");
  }

  public LogSequenceNumber appendChange(ChangeRecord change) {
    Objects.requireNonNull(change, "change");
    context.invoked("WalService.appendChange");
    return context.lsn();
  }

  public void commitDurably(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    context.invoked("WalService.commitDurably");
  }

  public void ensureDurable(LogSequenceNumber lsn) {
    Objects.requireNonNull(lsn, "lsn");
    context.invoked("WalService.ensureDurable");
    log.flush(lsn);
  }

  public LogView readLog(LogFilter filter) {
    Objects.requireNonNull(filter, "filter");
    context.invoked("WalService.readLog");
    return log.read(filter);
  }

  public void prepareLineage(UUID id, SealedGeneration base, RecoveryHighWater highWater) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(highWater, "highWater");
    context.invoked("WalService.prepareLineage");
    log.prepareLineage(id, base, highWater);
  }

  public void activate(GenerationManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    context.invoked("WalService.activate");
    log.activate(manifest);
  }
}
