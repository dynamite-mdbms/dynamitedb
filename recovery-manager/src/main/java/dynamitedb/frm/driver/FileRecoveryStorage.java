package dynamitedb.frm.driver;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.HistoricalRecoveryStorage;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Storage file-backed buat driver. Ini test double, bukan SM production.
 *
 * <p>Cukup buat latihan recovery antar-JVM: record image, page LSN per record, snapshot, stage, dan
 * switch generation.
 */
public final class FileRecoveryStorage
    implements RecoveryPageStore, HistoricalRecoveryStorage, StorageGenerationAdmin {
  private final Path records;
  private final Path snapshots;
  private final Path stages;
  private final UUID lineage;
  private final GenerationManifestStore manifests;

  public FileRecoveryStorage(Path root, UUID lineage, GenerationManifestStore manifests) {
    this.records = root.resolve("records");
    this.snapshots = root.resolve("snapshots");
    this.stages = root.resolve("stages");
    this.lineage = Objects.requireNonNull(lineage, "lineage");
    this.manifests = Objects.requireNonNull(manifests, "manifests");
    createDirectories(records);
    createDirectories(snapshots);
    createDirectories(stages);
  }

  @Override
  public synchronized RecordImage readRecord(RecordId rid) {
    return readAt(records, rid);
  }

  @Override
  public synchronized void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
    applyAt(records, mutation, lsn);
  }

  @Override
  public void rebuildIndexes() {
    // driver nggak punya secondary index
  }

  @Override
  public void flushDirtyThrough(LogSequenceNumber lsn) {
    // apply langsung nulis ke disk; nggak ada buffer pool di sini
  }

  @Override
  public synchronized SnapshotMetadata captureSnapshot(RecoveryBoundary boundary) {
    UUID id = UUID.randomUUID();
    Path destination = snapshots.resolve(id.toString());
    copyDirectory(records, destination);
    return new SnapshotMetadata(
        id, lineage, boundary.inclusiveLsn(), boundary.commitTime().orElse(Instant.now()));
  }

  @Override
  public synchronized RecoveryStage createStage(SnapshotMetadata snapshot, UUID id) {
    Path source = snapshots.resolve(snapshot.id().toString());
    Path destination = stages.resolve(id.toString());
    copyDirectory(source, destination);
    return new RecoveryStage(id);
  }

  @Override
  public synchronized void replayInStage(
      RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn) {
    applyAt(stages.resolve(stage.id().toString()), change, lsn);
  }

  @Override
  public void rebuildStageIndexes(RecoveryStage stage) {
    // stage juga nggak punya index
  }

  @Override
  public SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
    copyDirectory(stages.resolve(stage.id().toString()), snapshots.resolve(stage.id().toString()));
    return new SealedGeneration(stage.id(), boundary);
  }

  @Override
  public synchronized void materializeSnapshot(
      SealedGeneration generation, SnapshotMetadata snapshot) {
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(snapshot, "snapshot");
    if (!generation.id().equals(snapshot.id())
        || snapshot.baseLsn().value() < generation.boundary().inclusiveLsn().value()) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Driver snapshot does not match its sealed generation");
    }
    if (!Files.isDirectory(snapshots.resolve(snapshot.id().toString()))) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Sealed driver snapshot was not materialized");
    }
  }

  @Override
  public synchronized GenerationManifest readActiveManifest() {
    return manifests.readActive();
  }

  @Override
  public synchronized void switchToPublishedGeneration(GenerationManifest manifest) {
    Path stage = stages.resolve(manifest.activeId().toString());
    if (!Files.isDirectory(stage)) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Published generation has no sealed stage: " + stage);
    }
    clearDirectory(records);
    copyDirectory(stage, records);
  }

  private RecordImage readAt(Path directory, RecordId rid) {
    Path data = dataPath(directory, rid);
    if (!Files.exists(data)) return RecordImage.absent();
    try {
      byte[] bytes = Files.readAllBytes(data);
      if (bytes.length < 5) throw corrupt("Short record image: " + data);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      if (buffer.get() == 0) return RecordImage.absent();
      int length = buffer.getInt();
      if (length < 0 || length != buffer.remaining())
        throw corrupt("Invalid record image: " + data);
      byte[] payload = new byte[length];
      buffer.get(payload);
      return new RecordImage(true, ImmutableBytes.copyOf(payload));
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Cannot read driver record: " + data, failure);
    }
  }

  private void applyAt(Path directory, RecoveryMutation mutation, LogSequenceNumber lsn) {
    Objects.requireNonNull(mutation, "mutation");
    Objects.requireNonNull(lsn, "lsn");
    createDirectories(directory);
    Path data = dataPath(directory, mutation.rid());
    Path lsnPath = lsnPath(directory, mutation.rid());
    long previous = readLsn(lsnPath);
    if (previous >= lsn.value()) return;

    RecordImage image = mutation.targetImage();
    byte[] payload = image.encoded().copy();
    ByteBuffer bytes = ByteBuffer.allocate(5 + payload.length);
    bytes.put((byte) (image.exists() ? 1 : 0));
    bytes.putInt(payload.length);
    bytes.put(payload);
    writeAtomically(data, bytes.array());
    writeAtomically(
        lsnPath, Long.toString(lsn.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private long readLsn(Path path) {
    if (!Files.exists(path)) return -1;
    try {
      return Long.parseLong(Files.readString(path).trim());
    } catch (IOException | NumberFormatException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Cannot read driver page LSN: " + path, failure);
    }
  }

  private Path dataPath(Path directory, RecordId rid) {
    return directory.resolve(
        "r-"
            + rid.tableId()
            + "-"
            + rid.pageNumber()
            + "-"
            + rid.slotNumber()
            + "-"
            + rid.generation()
            + ".bin");
  }

  private Path lsnPath(Path directory, RecordId rid) {
    return directory.resolve(
        "r-"
            + rid.tableId()
            + "-"
            + rid.pageNumber()
            + "-"
            + rid.slotNumber()
            + "-"
            + rid.generation()
            + ".lsn");
  }

  private static void writeAtomically(Path path, byte[] bytes) {
    try {
      createDirectories(path.getParent());
      Path temporary = Files.createTempFile(path.getParent(), ".frm-driver-", ".tmp");
      Files.write(temporary, bytes);
      try {
        Files.move(
            temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Cannot write driver record: " + path, failure);
    }
  }

  private static void copyDirectory(Path source, Path destination) {
    try {
      createDirectories(destination);
      if (!Files.isDirectory(source)) return;
      try (var paths = Files.walk(source)) {
        for (Path path : paths.toList()) {
          Path target = destination.resolve(source.relativize(path).toString());
          if (Files.isDirectory(path)) {
            createDirectories(target);
          } else {
            createDirectories(target.getParent());
            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Cannot copy driver generation: " + source, failure);
    }
  }

  private static void clearDirectory(Path directory) {
    try {
      if (!Files.exists(directory)) {
        createDirectories(directory);
        return;
      }
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          if (!path.equals(directory)) Files.deleteIfExists(path);
        }
      }
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Cannot clear driver generation: " + directory, failure);
    }
  }

  private static void createDirectories(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Cannot create driver directory: " + directory, failure);
    }
  }

  private static DbmsException corrupt(String message) {
    return new DbmsException(ErrorCode.CORRUPT_LOG, message);
  }
}
