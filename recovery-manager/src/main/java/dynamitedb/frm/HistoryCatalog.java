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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Resolves historical targets and verifies that retained snapshot/WAL history covers them. */
public final class HistoryCatalog {
  private static final String SNAPSHOT_FILE = "recovery-snapshots.properties";

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
    this.snapshots = new CopyOnWriteArrayList<>();
    if (log != null && !log.isMock())
      this.snapshots.addAll(readSnapshotCatalog(log.rootDirectory()));
    for (SnapshotMetadata snapshot : Objects.requireNonNull(snapshots, "snapshots")) {
      mergeSnapshot(snapshot);
    }
    if (this.snapshots.isEmpty()) {
      throw new IllegalArgumentException("At least one recovery snapshot is required");
    }
    if (log != null && !log.isMock()) {
      for (SnapshotMetadata snapshot : this.snapshots) log.registerHistory(snapshot.walLineage());
      persistSnapshotCatalog(log.rootDirectory(), this.snapshots);
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
    return plan(target).boundary();
  }

  RecoveryPlan plan(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoryCatalog.validateCoverage");
    if (log == null) {
      return new RecoveryPlan(context.boundary(), baseSnapshot, List.of());
    }
    GenerationManifest active = activeManifest();
    if (snapshots.stream().noneMatch(s -> s.walLineage().equals(active.walLineage()))) {
      throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "No snapshot for active WAL lineage");
    }
    List<LogRecord> records = history();
    Map<TransactionId, TransactionEventLogRecord> commits = commits(records);
    SnapshotMetadata snapshot;
    RecoveryBoundary boundary;
    if (target.committedTx().isPresent()) {
      TransactionEventLogRecord commit = commits.get(target.committedTx().orElseThrow());
      if (commit == null) {
        throw new DbmsException(
            ErrorCode.RECOVERY_FAILURE, "Transaction target is not committed in retained history");
      }
      boundary = new RecoveryBoundary(commit.lsn, commit.commitTime);
      snapshot = selectSnapshot(commit.walLineage, boundary, commit.commitTime.orElseThrow());
    } else {
      Instant time = target.commitTime().orElseThrow();
      snapshot = snapshotAtOrBefore(time);
      if (snapshot == null) {
        throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Target predates retained history");
      }
      List<LogRecord> branch =
          records.stream()
              .filter(record -> record.walLineage.equals(snapshot.walLineage()))
              .filter(record -> record.lsn.value() >= snapshot.baseLsn().value())
              .toList();
      TransactionEventLogRecord lastCommit =
          commits(branch).values().stream()
              .filter(commit -> !commit.commitTime.orElseThrow().isAfter(time))
              .max(Comparator.comparingLong(commit -> commit.lsn.value()))
              .orElse(null);
      long lsn = snapshot.baseLsn().value();
      if (lastCommit != null) lsn = Math.max(lsn, lastCommit.lsn.value());
      Instant boundaryTime =
          lastCommit == null ? snapshot.createdAt() : lastCommit.commitTime.orElseThrow();
      boundary = new RecoveryBoundary(new LogSequenceNumber(lsn), Optional.of(boundaryTime));
    }
    return new RecoveryPlan(
        boundary, snapshot, committedChanges(snapshot, boundary, records, commits));
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

  synchronized void registerSnapshot(SnapshotMetadata snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    for (SnapshotMetadata existing : snapshots) {
      if (existing.id().equals(snapshot.id())) {
        if (!existing.equals(snapshot)) {
          throw new DbmsException(
              ErrorCode.RECOVERY_FAILURE, "Snapshot ID already has other metadata");
        }
        return;
      }
    }
    var updated = new ArrayList<>(snapshots);
    updated.add(snapshot);
    if (log != null && !log.isMock()) log.registerHistory(snapshot.walLineage());
    if (log != null && !log.isMock()) persistSnapshotCatalog(log.rootDirectory(), updated);
    snapshots.add(snapshot);
  }

  private GenerationManifest activeManifest() {
    return manifestStore == null ? manifest : manifestStore.readActive();
  }

  private List<LogRecord> history() {
    List<LogRecord> records = new ArrayList<>();
    var fromByLineage = new HashMap<UUID, Long>();
    for (SnapshotMetadata snapshot : snapshots) {
      fromByLineage.merge(snapshot.walLineage(), snapshot.baseLsn().value(), Math::min);
    }
    fromByLineage.forEach(
        (lineage, from) ->
            log.scanHistory(lineage, new LogSequenceNumber(from)).forEachRemaining(records::add));
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

  private SnapshotMetadata selectSnapshot(
      UUID lineage, RecoveryBoundary boundary, Instant targetTime) {
    return snapshots.stream()
        .filter(snapshot -> snapshot.walLineage().equals(lineage))
        .filter(snapshot -> snapshot.baseLsn().value() <= boundary.inclusiveLsn().value())
        .filter(snapshot -> !snapshot.createdAt().isAfter(targetTime))
        .max(Comparator.comparingLong(snapshot -> snapshot.baseLsn().value()))
        .orElseThrow(
            () ->
                new DbmsException(
                    ErrorCode.RECOVERY_FAILURE, "No retained snapshot covers target"));
  }

  private SnapshotMetadata snapshotAtOrBefore(Instant time) {
    return snapshots.stream()
        .filter(snapshot -> !snapshot.createdAt().isAfter(time))
        .max(
            Comparator.comparing(SnapshotMetadata::createdAt)
                .thenComparingLong(snapshot -> snapshot.baseLsn().value()))
        .orElse(null);
  }

  private void mergeSnapshot(SnapshotMetadata snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    for (SnapshotMetadata existing : snapshots) {
      if (existing.id().equals(snapshot.id())) {
        if (!existing.equals(snapshot)) {
          throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Snapshot catalog ID conflict");
        }
        return;
      }
    }
    snapshots.add(snapshot);
  }

  private static List<SnapshotMetadata> readSnapshotCatalog(Path root) {
    Path path = root.resolve(SNAPSHOT_FILE);
    if (!Files.exists(path)) return List.of();
    Properties properties = new Properties();
    try (var input = Files.newInputStream(path)) {
      properties.load(input);
      var snapshots = new ArrayList<SnapshotMetadata>();
      for (String key : properties.stringPropertyNames()) {
        if (!key.startsWith("snapshot.") || !key.endsWith(".lineage")) continue;
        String prefix = key.substring(0, key.length() - "lineage".length());
        UUID id = UUID.fromString(required(properties, prefix + "id"));
        UUID lineage = UUID.fromString(properties.getProperty(key));
        LogSequenceNumber baseLsn =
            new LogSequenceNumber(Long.parseLong(required(properties, prefix + "baseLsn")));
        Instant createdAt = Instant.parse(required(properties, prefix + "createdAt"));
        snapshots.add(new SnapshotMetadata(id, lineage, baseLsn, createdAt));
      }
      return snapshots;
    } catch (IOException | IllegalArgumentException failure) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Cannot read retained snapshot catalog", failure);
    }
  }

  private static void persistSnapshotCatalog(Path root, List<SnapshotMetadata> snapshots) {
    Path temporary = null;
    try {
      Files.createDirectories(root);
      Properties properties = new Properties();
      for (SnapshotMetadata snapshot : snapshots) {
        String prefix = "snapshot." + snapshot.id() + ".";
        properties.setProperty(prefix + "id", snapshot.id().toString());
        properties.setProperty(prefix + "lineage", snapshot.walLineage().toString());
        properties.setProperty(prefix + "baseLsn", Long.toString(snapshot.baseLsn().value()));
        properties.setProperty(prefix + "createdAt", snapshot.createdAt().toString());
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      properties.store(bytes, "DynamiteDB retained recovery snapshots");
      temporary = Files.createTempFile(root, ".recovery-snapshots-", ".tmp");
      try (FileChannel channel =
          FileChannel.open(
              temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(
            temporary,
            root.resolve(SNAPSHOT_FILE),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        throw new DbmsException(
            ErrorCode.RECOVERY_FAILURE,
            "Filesystem does not support atomic snapshot catalog replacement",
            unsupported);
      }
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Cannot persist retained snapshot catalog", failure);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // A leftover temp file is not read as the catalog.
        }
      }
    }
  }

  private static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing snapshot metadata: " + key);
    }
    return value;
  }
}
