package dynamitedb.frm;

import dynamitedb.contracts.GenerationManifest;
import dynamitedb.contracts.RecoveryHighWater;
import dynamitedb.contracts.RecoveryTarget;
import dynamitedb.contracts.SealedGeneration;
import dynamitedb.contracts.SnapshotMetadata;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;
import java.util.UUID;

/** Configurable Milestone 1 snapshot/manifest responses, not a historical coverage validator. */
public final class HistoryCatalog {
  private final SnapshotMetadata baseSnapshot;
  private final GenerationManifest manifest;
  private final MockRecoveryContext context;

  public HistoryCatalog(SnapshotMetadata baseSnapshot, GenerationManifest manifest) {
    this(baseSnapshot, manifest, new MockRecoveryContext());
  }

  public HistoryCatalog(MockRecoveryContext context) {
    this(context.snapshot(), context.manifest(), context);
  }

  public HistoryCatalog(
      SnapshotMetadata baseSnapshot, GenerationManifest manifest, MockRecoveryContext context) {
    this.baseSnapshot = Objects.requireNonNull(baseSnapshot, "baseSnapshot");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.context = Objects.requireNonNull(context, "context");
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
