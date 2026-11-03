package dynamitedb.frm;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Configurable Milestone 1 snapshot/manifest responses, not a historical coverage validator. */
public final class HistoryCatalog {
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
  }

  public void validateCoverage(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoryCatalog.validateCoverage");
  }

  public SnapshotMetadata getBaseSnapshot(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoryCatalog.getBaseSnapshot");
    return baseSnapshot;
  }

  public GenerationManifest buildManifest(
      SealedGeneration generation, UUID walLineage, RecoveryHighWater highWater) {
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(walLineage, "walLineage");
    Objects.requireNonNull(highWater, "highWater");
    context.invoked("HistoryCatalog.buildManifest");
    return manifest;
  }
}
