package dynamitedb.frm.driver;

import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.AtomicGenerationManifestStore;
import dynamitedb.frm.CheckpointManager;
import dynamitedb.frm.CommitClock;
import dynamitedb.frm.CrashRecoveryManager;
import dynamitedb.frm.HistoricalRecoveryManager;
import dynamitedb.frm.HistoryCatalog;
import dynamitedb.frm.LogFile;
import dynamitedb.frm.LogManager;
import dynamitedb.frm.RecoveryInitializer;
import dynamitedb.frm.UndoManager;
import dynamitedb.frm.WalService;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Rakit komponen FRM real buat driver; CCM/SM-nya diganti port file-backed. */
final class RecoveryHarness implements AutoCloseable, RecoveryCoordinator {
  private static final TransactionId ZERO_TX = new TransactionId(0);

  final LogManager log;
  final WalService wal;
  final FileRecoveryStorage store;
  final UndoManager undo;
  final CheckpointManager checkpoint;
  final HistoricalRecoveryManager historical;
  final RecoveryInitializer initializer;

  private volatile RecoveryHighWater highWater =
      new RecoveryHighWater(new LogSequenceNumber(0), ZERO_TX, 0, Optional.empty());

  static RecoveryHarness fresh(Path root, String name) {
    Path directory = root.resolve(name);
    deleteDirectory(directory);
    return new RecoveryHarness(directory, true);
  }

  static RecoveryHarness open(Path root, String name) {
    return new RecoveryHarness(root.resolve(name), false);
  }

  private RecoveryHarness(Path directory, boolean fresh) {
    Objects.requireNonNull(directory, "directory");
    if (fresh) deleteDirectory(directory);
    createDirectories(directory);

    UUID initialLineage = lineageFor(directory);
    RecoveryHighWater zero =
        new RecoveryHighWater(new LogSequenceNumber(0), ZERO_TX, 0, Optional.empty());
    GenerationManifest initial =
        new GenerationManifest(
            UUID.nameUUIDFromBytes(("active:" + directory).getBytes(StandardCharsets.UTF_8)),
            initialLineage,
            new LogSequenceNumber(0),
            zero);
    var manifests =
        new AtomicGenerationManifestStore(directory, initial, new MockRecoveryContext());
    GenerationManifest active = fresh ? initial : manifests.readActive();
    UUID lineage = active.walLineage();

    Path walPath = LogFile.path(directory, lineage);
    long floor = Files.exists(walPath) ? readWalFloor(walPath) : 0;
    RecoveryHighWater seed =
        new RecoveryHighWater(new LogSequenceNumber(floor), ZERO_TX, 0, Optional.empty());
    this.log =
        Files.exists(walPath)
            ? LogManager.openExisting(directory, lineage, seed)
            : LogManager.createNew(directory, lineage, seed);

    this.wal = new WalService(new CommitClock(), log);
    this.store = new FileRecoveryStorage(directory, lineage, manifests);
    this.undo = new UndoManager(log, store);
    var crash = new CrashRecoveryManager(log, wal, manifests, store, undo, store);
    this.checkpoint = new CheckpointManager(this, store, log);
    SnapshotMetadata base =
        store.captureSnapshot(new RecoveryBoundary(new LogSequenceNumber(0), Optional.empty()));
    var history = new HistoryCatalog(manifests, log, List.of(base));
    this.historical =
        new HistoricalRecoveryManager(store, history, store, log, wal, manifests, this);
    this.initializer = new RecoveryInitializer(manifests, crash, this);
  }

  RecoveryHighWater highWater() {
    return highWater;
  }

  @Override
  public MaintenanceGuard acquireMaintenance() {
    return () -> {};
  }

  @Override
  public long transactionTimestamp(TransactionId tx) {
    return 0;
  }

  @Override
  public void initializeRecoveredState(RecoveryHighWater highWater) {
    this.highWater = Objects.requireNonNull(highWater, "highWater");
  }

  @Override
  public void close() {
    log.close();
  }

  private static UUID lineageFor(Path directory) {
    return UUID.nameUUIDFromBytes(
        ("dynamitedb-frm:" + directory.toAbsolutePath().normalize())
            .getBytes(StandardCharsets.UTF_8));
  }

  private static long readWalFloor(Path walPath) {
    try {
      byte[] header = Files.readAllBytes(walPath);
      if (header.length < 32) throw new IllegalStateException("Header WAL kepotong");
      return ByteBuffer.wrap(header, 24, 8).getLong();
    } catch (IOException failure) {
      throw new IllegalStateException("Gagal baca floor WAL: " + walPath, failure);
    }
  }

  static void deleteDirectory(Path directory) {
    if (!Files.exists(directory)) return;
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("Gagal reset direktori driver: " + directory, failure);
    }
  }

  private static void createDirectories(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("Gagal bikin direktori: " + directory, failure);
    }
  }
}
