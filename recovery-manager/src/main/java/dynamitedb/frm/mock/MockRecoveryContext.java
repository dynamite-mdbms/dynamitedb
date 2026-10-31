package dynamitedb.frm.mock;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.LogView;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.ChangeLogRecord;
import dynamitedb.frm.RecoveryState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Explicitly non-durable fixture responses and call recording for Milestone 1 only. */
public final class MockRecoveryContext {
  private final List<String> calls = new ArrayList<>();
  private final Map<String, DbmsException> failures = new HashMap<>();
  private final LogSequenceNumber lsn;
  private final Instant commitTime;
  private final RecoveryStatus status;

  public MockRecoveryContext() {
    this(new LogSequenceNumber(1), Instant.EPOCH, RecoveryStatus.SUCCESS);
  }

  public MockRecoveryContext(LogSequenceNumber lsn, Instant commitTime, RecoveryStatus status) {
    this.lsn = Objects.requireNonNull(lsn, "lsn");
    this.commitTime = Objects.requireNonNull(commitTime, "commitTime");
    this.status = Objects.requireNonNull(status, "status");
  }

  public synchronized void invoked(String operation) {
    calls.add(Objects.requireNonNull(operation, "operation"));
    DbmsException failure = failures.get(operation);
    if (failure != null) {
      throw failure;
    }
  }

  public synchronized void failOn(String operation, DbmsException failure) {
    failures.put(
        Objects.requireNonNull(operation, "operation"), Objects.requireNonNull(failure, "failure"));
  }

  public synchronized List<String> calls() {
    return List.copyOf(calls);
  }

  public LogSequenceNumber lsn() {
    return lsn;
  }

  public Instant commitTime() {
    return commitTime;
  }

  public TransactionId tx() {
    return new TransactionId(1);
  }

  public UUID lineage() {
    return new UUID(0, 2);
  }

  public RecordId rid() {
    return new RecordId(1, 1, 0, 1);
  }

  public RecordImage image() {
    return new RecordImage(true, ImmutableBytes.copyOf(new byte[] {42}));
  }

  public ChangeRecord change() {
    return new ChangeRecord(
        tx(),
        "mock_table",
        rid(),
        new RecordImage(false, ImmutableBytes.copyOf(new byte[0])),
        image());
  }

  public RecoveryBoundary boundary() {
    return new RecoveryBoundary(lsn, Optional.of(commitTime));
  }

  public SnapshotMetadata snapshot() {
    return new SnapshotMetadata(new UUID(0, 3), lineage(), lsn, commitTime);
  }

  public RecoveryStage stage() {
    return new RecoveryStage(new UUID(0, 4));
  }

  public SealedGeneration sealed() {
    return new SealedGeneration(new UUID(0, 5), boundary());
  }

  public RecoveryHighWater highWater() {
    return new RecoveryHighWater(lsn, tx(), 1, Optional.of(commitTime));
  }

  public GenerationManifest manifest() {
    return new GenerationManifest(sealed().id(), lineage(), lsn, highWater());
  }

  public RecoveryResult result() {
    return new RecoveryResult(status, highWater(), "MOCK ONLY: no durable recovery performed");
  }

  public LogView logView() {
    return new LogView(List.of("MOCK ONLY: no persistent WAL"));
  }

  public ChangeLogRecord record() {
    return new ChangeLogRecord(lsn, Optional.empty(), lineage(), change());
  }

  public ImmutableBytes frame() {
    return ImmutableBytes.copyOf(new byte[] {77, 79, 67, 75});
  }

  public RecoveryState state() {
    return new RecoveryState(Set.of(tx()), Set.of(), lsn, highWater());
  }
}
