package dynamitedb.sm.generation;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/** Snapshot, historical recovery stage, dan generation switch (FR-05, contract G). */
public final class GenerationStore {
  private static final List<String> STORAGE_FILES =
      List.of("pages.db", "pages.pending", "heap.dir", "catalog.db");
  private static final String META = "generation.meta";
  private static final String MARKER = "active.generation";
  private static final LogSequenceNumber EVERYTHING = new LogSequenceNumber(Long.MAX_VALUE);

  private final Path root;
  private final GenerationManifestStore manifests;
  private final BufferPool buffer;
  private final CatalogManager catalog;
  private final IndexManager indexes;
  private final RecordStore records;

  private final Map<UUID, RecordStore> stageRecords = new HashMap<>();
  private final Map<UUID, BufferPool> stageBuffers = new HashMap<>();

  public GenerationStore(
      Path root,
      GenerationManifestStore manifests,
      BufferPool buffer,
      CatalogManager catalog,
      IndexManager indexes,
      RecordStore records) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    this.manifests = Objects.requireNonNull(manifests, "manifests");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
    this.records = Objects.requireNonNull(records, "records");
  }

  /** Flush dirty pages lalu copy active generation jadi snapshot (saat maintenance). */
  public synchronized SnapshotMetadata captureSnapshot(RecoveryBoundary boundary) {
    Objects.requireNonNull(boundary, "boundary");
    var snapshot =
        new SnapshotMetadata(
            UUID.randomUUID(),
            manifests.readActive().walLineage(),
            boundary.inclusiveLsn(),
            Instant.now());
    return captureSnapshot(snapshot);
  }

  public synchronized SnapshotMetadata captureSnapshot(SnapshotMetadata snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    buffer.flushDirtyThrough(EVERYTHING);
    if (!snapshot.walLineage().equals(manifests.readActive().walLineage())) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Snapshot WAL lineage is not active");
    }
    Path target = snapshotDir(snapshot.id());
    Path temp = target.resolveSibling(snapshot.id() + ".tmp");
    try {
      deleteTree(temp);
      copyStorageFiles(root, temp);
      var meta = new Properties();
      meta.setProperty("id", snapshot.id().toString());
      meta.setProperty("walLineage", snapshot.walLineage().toString());
      meta.setProperty("baseLsn", Long.toString(snapshot.baseLsn().value()));
      meta.setProperty("createdAt", snapshot.createdAt().toString());
      writeProperties(temp.resolve(META), meta);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Gagal create snapshot", e);
    }
    return snapshot;
  }

  /** Buat isolated stage dari snapshot tanpa menyentuh active data. */
  public synchronized RecoveryStage createStage(SnapshotMetadata snapshot, UUID id) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(id, "id");
    if (stageRecords.containsKey(id)) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Stage sudah ada: " + id);
    }
    Path source = snapshotDir(snapshot.id());
    Properties meta = readProperties(source.resolve(META));
    if (!snapshot.walLineage().toString().equals(meta.getProperty("walLineage"))
        || !Long.toString(snapshot.baseLsn().value()).equals(meta.getProperty("baseLsn"))) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Snapshot metadata tidak cocok: " + snapshot.id());
    }
    Path stageDir = stageDir(id);
    try {
      deleteTree(stageDir);
      copyStorageFiles(source, stageDir);
    } catch (IOException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Gagal create stage " + id, e);
    }
    // Stage tanpa WAL: bila crash sebelum seal, stage dibuat ulang.
    var stageBuffer = new BufferPool(new DiskManager(stageDir), lsn -> {});
    var stageCatalog = new CatalogManager(stageDir);
    var serializer = new RecordSerializer();
    var stageHeap = new HeapFile(stageDir, stageBuffer, serializer, stageCatalog);
    var stageIndexes = new IndexManager(stageHeap, serializer);
    stageRecords.put(
        id, new RecordStore(stageHeap, stageBuffer, serializer, stageIndexes, stageCatalog));
    stageBuffers.put(id, stageBuffer);
    return new RecoveryStage(id);
  }

  /** Replay committed change ke stage saja (idempotent by pageLSN). */
  public synchronized void replayInStage(
      RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn) {
    stage(stage).applyRecovery(change, lsn);
  }

  /** Rebuild index dan statistics stage dari heap stage. */
  public synchronized void rebuildStageIndexes(RecoveryStage stage) {
    stage(stage).rebuildIndexes();
  }

  /** Flush dan seal stage; aktif setelah FRM publish manifest. */
  public synchronized SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
    Objects.requireNonNull(boundary, "boundary");
    stage(stage);
    UUID id = stage.id();
    stageBuffers.get(id).flushDirtyThrough(EVERYTHING);
    Path source = stageDir(id);
    Path target = generationDir(id);
    try {
      var meta = new Properties();
      meta.setProperty("id", id.toString());
      meta.setProperty("boundaryLsn", Long.toString(boundary.inclusiveLsn().value()));
      boundary.commitTime().ifPresent(time -> meta.setProperty("commitTime", time.toString()));
      writeProperties(source.resolve(META), meta);
      Files.createDirectories(target.getParent());
      deleteTree(target);
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Gagal seal stage " + id, e);
    }
    stageRecords.remove(id);
    stageBuffers.remove(id);
    return new SealedGeneration(id, boundary);
  }

  /** Materialize a sealed branch generation as a durable snapshot before FRM registers it. */
  public synchronized void materializeSnapshot(
      SealedGeneration generation, SnapshotMetadata snapshot) {
    Objects.requireNonNull(generation, "generation");
    Objects.requireNonNull(snapshot, "snapshot");
    if (!generation.id().equals(snapshot.id())
        || snapshot.baseLsn().value() < generation.boundary().inclusiveLsn().value()) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Snapshot metadata does not match its sealed generation");
    }

    Path source = generationDir(generation.id());
    if (!Files.isDirectory(source)) {
      throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Sealed generation not found: " + source);
    }
    Properties generationMetadata = readProperties(source.resolve(META));
    if (!generation.id().toString().equals(generationMetadata.getProperty("id"))
        || !Long.toString(generation.boundary().inclusiveLsn().value())
            .equals(generationMetadata.getProperty("boundaryLsn"))
        || !Objects.equals(
            generation.boundary().commitTime().map(Instant::toString).orElse(null),
            generationMetadata.getProperty("commitTime"))) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Sealed generation metadata does not match its identity");
    }

    Path target = snapshotDir(snapshot.id());
    if (Files.exists(target)) {
      verifySnapshotMetadata(target, snapshot);
      return;
    }
    Path temporary = target.resolveSibling(snapshot.id() + ".tmp");
    try {
      Files.createDirectories(target.getParent());
      deleteTree(temporary);
      copyStorageFiles(source, temporary);
      var metadata = new Properties();
      metadata.setProperty("id", snapshot.id().toString());
      metadata.setProperty("walLineage", snapshot.walLineage().toString());
      metadata.setProperty("baseLsn", Long.toString(snapshot.baseLsn().value()));
      metadata.setProperty("createdAt", snapshot.createdAt().toString());
      writeProperties(temporary.resolve(META), metadata);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileAlreadyExistsException raced) {
        verifySnapshotMetadata(target, snapshot);
      }
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Gagal materialize snapshot " + snapshot.id(), failure);
    } finally {
      try {
        deleteTree(temporary);
      } catch (IOException ignored) {
        // An incomplete temporary directory is never treated as a materialized snapshot.
      }
    }
  }

  /** Read active manifest dari GenerationManifestStore milik FRM. */
  public synchronized GenerationManifest readActiveManifest() {
    return manifests.readActive();
  }

  /** Install published generation lalu refresh buffer, catalog, heap, index (idempotent). */
  public synchronized void switchToPublishedGeneration(GenerationManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    if (!manifest.equals(manifests.readActive())) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Manifest belum di-publish FRM: " + manifest.activeId());
    }
    UUID target = manifest.activeId();
    Optional<UUID> installed = installedGeneration();
    if (installed.isPresent() && installed.get().equals(target)) {
      return;
    }
    Path source = generationDir(target);
    if (!Files.isDirectory(source)) {
      if (installed.isEmpty()) {
        return;
      }
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Published generation tidak ditemukan: " + target);
    }
    // Discard frame lama dulu supaya page lama tidak ter-flush ke file baru.
    buffer.discardAll();
    try {
      copyStorageFiles(source, root);
      writeDurably(root.resolve(MARKER), target.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Gagal install generation " + target + ", ulangi activation",
          e);
    }
    catalog.reload();
    records.resetAfterGenerationSwitch();
    records.rebuildIndexes();
  }

  private RecordStore stage(RecoveryStage stage) {
    Objects.requireNonNull(stage, "stage");
    RecordStore store = stageRecords.get(stage.id());
    if (store == null) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Stage tidak dikenal: " + stage.id());
    }
    return store;
  }

  private Optional<UUID> installedGeneration() {
    Path marker = root.resolve(MARKER);
    if (!Files.exists(marker)) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(Files.readString(marker, StandardCharsets.UTF_8).trim()));
    } catch (IOException | IllegalArgumentException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Active generation marker rusak", e);
    }
  }

  private Path snapshotDir(UUID id) {
    return root.resolve("snapshots").resolve(id.toString());
  }

  private Path stageDir(UUID id) {
    return root.resolve("stages").resolve(id.toString());
  }

  private Path generationDir(UUID id) {
    return root.resolve("generations").resolve(id.toString());
  }

  private static void verifySnapshotMetadata(Path directory, SnapshotMetadata snapshot) {
    Properties metadata = readProperties(directory.resolve(META));
    if (!snapshot.id().toString().equals(metadata.getProperty("id"))
        || !snapshot.walLineage().toString().equals(metadata.getProperty("walLineage"))
        || !Long.toString(snapshot.baseLsn().value()).equals(metadata.getProperty("baseLsn"))
        || !snapshot.createdAt().toString().equals(metadata.getProperty("createdAt"))) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Snapshot ID already exists with different metadata");
    }
  }

  /** Copy storage files secara atomic; file yang tidak ada di source dihapus. */
  private static void copyStorageFiles(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    for (String name : STORAGE_FILES) {
      Path source = from.resolve(name);
      Path target = to.resolve(name);
      if (Files.exists(source)) {
        writeDurably(target, Files.readAllBytes(source));
      } else {
        Files.deleteIfExists(target);
      }
    }
  }

  private static void writeDurably(Path target, byte[] content) throws IOException {
    Files.createDirectories(target.getParent());
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");
    try (var channel =
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      var data = ByteBuffer.wrap(content);
      while (data.hasRemaining()) {
        channel.write(data);
      }
      channel.force(true);
    }
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void writeProperties(Path target, Properties properties) throws IOException {
    var bytes = new ByteArrayOutputStream();
    properties.store(bytes, "DynamiteDB storage generation");
    writeDurably(target, bytes.toByteArray());
  }

  private static Properties readProperties(Path file) {
    var properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      properties.load(in);
    } catch (IOException e) {
      throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Snapshot tidak ditemukan: " + file, e);
    }
    return properties;
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
