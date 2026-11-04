package dynamitedb.frm;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves historical targets and verifies that retained snapshot/WAL history covers them. */
public final class HistoryCatalog {
  record RecoveryPlan(
      RecoveryBoundary boundary, SnapshotMetadata snapshot, List<ChangeLogRecord> changes) {}

  private final SnapshotMetadata baseSnapshot;
  private final GenerationManifest manifest;
  private final MockRecoveryContext context;
  private final GenerationManifestStore manifestStore;
  private final LogManager log;
  private final List<SnapshotMetadata> snapshots;

  public HistoryCatalog(SnapshotMetadata baseSnapshot, GenerationManifest manifest) {
    this(baseSnapshot, manifest, new MockRecoveryContext());
  }

  public HistoryCatalog(MockRecoveryContext context) {
    this(context.snapshot(), context.manifest(), context);
  }

  public HistoryCatalog(
      SnapshotMetadata baseSnapshot, GenerationManifest manifest, MockRecoveryContext context) {
    this(baseSnapshot, manifest, context, null, null, List.of(baseSnapshot));
  }

  public HistoryCatalog(
      GenerationManifestStore manifestStore, LogManager log, List<SnapshotMetadata> snapshots) {
    this(manifestStore, log, snapshots, new MockRecoveryContext());
  }

  public HistoryCatalog(
      GenerationManifestStore manifestStore,
      LogManager log,
      List<SnapshotMetadata> snapshots,
      MockRecoveryContext context) {
    this(
        context.snapshot(),
        context.manifest(),
        context,
        Objects.requireNonNull(manifestStore, "manifestStore"),
        Objects.requireNonNull(log, "log"),
        snapshots);
  }

  private HistoryCatalog(
      SnapshotMetadata baseSnapshot,
      GenerationManifest manifest,
      MockRecoveryContext context,
      GenerationManifestStore manifestStore,
      LogManager log,
      List<SnapshotMetadata> snapshots) {
    this.baseSnapshot = Objects.requireNonNull(baseSnapshot, "baseSnapshot");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.context = Objects.requireNonNull(context, "context");
    this.manifestStore = manifestStore;
    this.log = log;
    this.snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
    if (this.snapshots.isEmpty()) {
      throw new IllegalArgumentException("At least one recovery snapshot is required");
    }
  }

  public void validateCoverage(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    plan(target);
  }

  public RecoveryBoundary resolveBoundary(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoryCatalog.resolveBoundary");
    if (log == null) {
      return context.boundary();
    }
    GenerationManifest active = activeManifest();
    List<LogRecord> records = history(active);
    return resolveBoundary(target, active, records, commits(records));
  }

  RecoveryPlan plan(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoryCatalog.validateCoverage");
    if (log == null) {
      return new RecoveryPlan(context.boundary(), baseSnapshot, List.of());
    }
    GenerationManifest active = activeManifest();
    List<LogRecord> records = history(active);
    Map<TransactionId, TransactionEventLogRecord> commits = commits(records);
    RecoveryBoundary boundary = resolveBoundary(target, active, records, commits);
    SnapshotMetadata snapshot = selectSnapshot(active.walLineage(), boundary);
    return new RecoveryPlan(
        boundary, snapshot, committedChanges(snapshot, boundary, records, commits));
  }

  private RecoveryBoundary resolveBoundary(
      RecoveryTarget target,
      GenerationManifest active,
      List<LogRecord> records,
      Map<TransactionId, TransactionEventLogRecord> commits) {
    if (target.committedTx().isPresent()) {
      TransactionId tx = target.committedTx().orElseThrow();
      TransactionEventLogRecord commit = commits.get(tx);
      if (commit == null) {
        throw new DbmsException(
            ErrorCode.RECOVERY_FAILURE, "Transaction target is not committed in retained history");
      }
      return new RecoveryBoundary(commit.lsn, commit.commitTime);
    }

    Instant time = target.commitTime().orElseThrow();
    TransactionEventLogRecord lastCommit =
        commits.values().stream()
            .filter(commit -> !commit.commitTime.orElseThrow().isAfter(time))
            .max(Comparator.comparingLong(commit -> commit.lsn.value()))
            .orElse(null);
    SnapshotMetadata snapshot = snapshotAtOrBefore(active.walLineage(), time);
    if (lastCommit == null && snapshot == null) {
      throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Target predates retained history");
    }
    long lsn = snapshot == null ? lastCommit.lsn.value() : snapshot.baseLsn().value();
    if (lastCommit != null) {
      lsn = Math.max(lsn, lastCommit.lsn.value());
    }
    return new RecoveryBoundary(new LogSequenceNumber(lsn), Optional.of(time));
  }

  public SnapshotMetadata getBaseSnapshot(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoryCatalog.getBaseSnapshot");
    if (log == null) {
      return baseSnapshot;
    }
    return plan(target).snapshot();
  }

  /** Returns committed row changes after the snapshot and through the inclusive boundary. */
  private List<ChangeLogRecord> committedChanges(
      SnapshotMetadata snapshot,
      RecoveryBoundary boundary,
      List<LogRecord> records,
      Map<TransactionId, TransactionEventLogRecord> commits) {
    return records.stream()
        .filter(ChangeLogRecord.class::isInstance)
        .map(ChangeLogRecord.class::cast)
        .filter(change -> change.walLineage.equals(snapshot.walLineage()))
        .filter(change -> change.lsn.value() > snapshot.baseLsn().value())
        .filter(change -> change.lsn.value() <= boundary.inclusiveLsn().value())
        .filter(
            change -> {
              TransactionEventLogRecord commit = commits.get(change.change.tx());
              return commit != null
                  && commit.lsn.value() <= boundary.inclusiveLsn().value()
                  && boundary.commitTime().stream()
                      .noneMatch(time -> commit.commitTime.orElseThrow().isAfter(time));
            })
        .sorted(Comparator.comparingLong(change -> change.lsn.value()))
        .toList();
  }

  public GenerationManifest buildManifest(
      SealedGeneration generation, UUID walLineage, RecoveryHighWater highWater) {
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(walLineage, "walLineage");
    Objects.requireNonNull(highWater, "highWater");
    context.invoked("HistoryCatalog.buildManifest");
    return new GenerationManifest(
        generation.id(), walLineage, generation.boundary().inclusiveLsn(), highWater);
  }

  private GenerationManifest activeManifest() {
    return manifestStore == null ? manifest : manifestStore.readActive();
  }

  private List<LogRecord> history(GenerationManifest active) {
    long from =
        snapshots.stream()
            .filter(snapshot -> snapshot.walLineage().equals(active.walLineage()))
            .mapToLong(snapshot -> snapshot.baseLsn().value())
            .min()
            .orElseThrow(
                () ->
                    new DbmsException(
                        ErrorCode.RECOVERY_FAILURE, "No snapshot for active WAL lineage"));
    List<LogRecord> records = new ArrayList<>();
    log.scanHistory(active.walLineage(), new LogSequenceNumber(from))
        .forEachRemaining(records::add);
    records.removeIf(record -> !record.walLineage.equals(active.walLineage()));
    records.removeIf(record -> record.lsn.value() < from);
    records.sort(Comparator.comparingLong(record -> record.lsn.value()));
    return records;
  }

  private Map<TransactionId, TransactionEventLogRecord> commits(List<LogRecord> records) {
    Map<TransactionId, TransactionEventLogRecord> commits = new HashMap<>();
    for (LogRecord record : records) {
      if (record instanceof TransactionEventLogRecord event && event.event == TxEvent.COMMIT) {
        commits.put(event.tx.orElseThrow(), event);
      }
    }
    return commits;
  }

  private SnapshotMetadata selectSnapshot(UUID lineage, RecoveryBoundary boundary) {
    return snapshots.stream()
        .filter(snapshot -> snapshot.walLineage().equals(lineage))
        .filter(snapshot -> snapshot.baseLsn().value() <= boundary.inclusiveLsn().value())
        .max(Comparator.comparingLong(snapshot -> snapshot.baseLsn().value()))
        .orElseThrow(
            () ->
                new DbmsException(
                    ErrorCode.RECOVERY_FAILURE, "No retained snapshot covers target"));
  }

  private SnapshotMetadata snapshotAtOrBefore(UUID lineage, Instant time) {
    return snapshots.stream()
        .filter(snapshot -> snapshot.walLineage().equals(lineage))
        .filter(snapshot -> !snapshot.createdAt().isAfter(time))
        .max(Comparator.comparingLong(snapshot -> snapshot.baseLsn().value()))
        .orElse(null);
  }
}
