package dynamitedb.frm;

import dynamitedb.contracts.GenerationManifest;
import dynamitedb.contracts.RecoveryHighWater;
import dynamitedb.contracts.RecoveryTarget;
import dynamitedb.contracts.SealedGeneration;
import dynamitedb.contracts.SnapshotMetadata;
import java.util.Objects;
import java.util.UUID;

/** Configurable milestone 1 mock for historical snapshot metadata. */
public final class HistoryCatalog {
  private final SnapshotMetadata baseSnapshot;
  private final GenerationManifest manifest;

  public HistoryCatalog(SnapshotMetadata baseSnapshot, GenerationManifest manifest) {
    this.baseSnapshot = Objects.requireNonNull(baseSnapshot, "baseSnapshot");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
  }

  public void validateCoverage(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
  }

  public SnapshotMetadata getBaseSnapshot(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    return baseSnapshot;
  }

  public GenerationManifest buildManifest(
      SealedGeneration generation, UUID walLineage, RecoveryHighWater highWater) {
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(walLineage, "walLineage");
    Objects.requireNonNull(highWater, "highWater");
    return manifest;
  }
}
