package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.AtomicGenerationManifestStore;
import dynamitedb.frm.CommitClock;
import dynamitedb.frm.CrashRecoveryManager;
import dynamitedb.frm.HistoricalRecoveryManager;
import dynamitedb.frm.HistoryCatalog;
import dynamitedb.frm.LogManager;
import dynamitedb.frm.RecoveryInitializer;
import dynamitedb.frm.UndoManager;
import dynamitedb.frm.WalService;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.facade.StorageManagerImpl;
import dynamitedb.sm.facade.StorageRecoveryAdapter;
import dynamitedb.sm.generation.GenerationStore;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentHistoricalRecoveryTest {
  private static final Schema SCHEMA =
      new Schema(List.of(new Column("items", "id", DataType.INTEGER, 0, false)));
  private static final RecoveryHighWater ZERO =
      new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());

  @TempDir Path directory;

  @Test
  void historicalRestoreSnapshotCanBeUsedAgainAfterRestart() throws Exception {
    RuntimeGraph first = RuntimeGraph.create(directory, null);
    first.catalog.createTable("items", SCHEMA);
    first.insert(1, 1);
    RecoveryHighWater baseline = first.log.readHighWater();
    SnapshotMetadata base =
        first.generations.captureSnapshot(
            new RecoveryBoundary(baseline.lsn(), baseline.maxCommitTime()));
    first.manifests.publishAtomically(
        new GenerationManifest(base.id(), base.walLineage(), base.baseLsn(), baseline));
    first.history = new HistoryCatalog(first.manifests, first.log, List.of(base));
    first.historical = first.createHistoricalManager();

    first.insert(2, 2);
    first.insert(3, 3);
    RecoveryResult firstRestore =
        first.historical.recoverTo(
            new RecoveryTarget(Optional.empty(), Optional.of(new TransactionId(2))));

    assertEquals(RecoveryStatus.SUCCESS, firstRestore.status(), firstRestore.message());
    assertEquals(List.of(1, 2), first.values());
    UUID firstBranch = first.manifests.readActive().activeId();
    assertTrue(
        Files.isDirectory(directory.resolve("snapshots").resolve(firstBranch.toString())),
        "FRM must register a physical snapshot for the new branch");
    first.close();

    RuntimeGraph restarted = RuntimeGraph.create(directory, base);
    RecoveryResult startup = restarted.initializer().openExisting();
    assertEquals(RecoveryStatus.SUCCESS, startup.status(), startup.message());
    restarted.history = new HistoryCatalog(restarted.manifests, restarted.log, List.of(base));
    restarted.historical = restarted.createHistoricalManager();

    restarted.insert(4, 4);
    RecoveryResult secondRestore =
        restarted.historical.recoverTo(
            new RecoveryTarget(Optional.empty(), Optional.of(new TransactionId(4))));

    assertEquals(RecoveryStatus.SUCCESS, secondRestore.status(), secondRestore.message());
    assertEquals(List.of(1, 2, 4), restarted.values());
    restarted.close();
  }

  private static final class RuntimeGraph implements AutoCloseable {
    private final Path root;
    private final AtomicGenerationManifestStore manifests;
    private final LogManager log;
    private final WalService wal;
    private final CatalogManager catalog;
    private final StorageManagerImpl storage;
    private final GenerationStore generations;
    private final StorageRecoveryAdapter storageRecovery;
    private final UndoManager undo;
    private final RecoveryCoordinator coordinator = new NoopCoordinator();
    private HistoryCatalog history;
    private HistoricalRecoveryManager historical;

    private RuntimeGraph(
        Path root,
        AtomicGenerationManifestStore manifests,
        LogManager log,
        WalService wal,
        CatalogManager catalog,
        StorageManagerImpl storage,
        GenerationStore generations,
        StorageRecoveryAdapter storageRecovery,
        UndoManager undo) {
      this.root = root;
      this.manifests = manifests;
      this.log = log;
      this.wal = wal;
      this.catalog = catalog;
      this.storage = storage;
      this.generations = generations;
      this.storageRecovery = storageRecovery;
      this.undo = undo;
    }

    private static RuntimeGraph create(Path root, SnapshotMetadata base) {
      UUID lineage = UUID.randomUUID();
      GenerationManifest initial =
          new GenerationManifest(UUID.randomUUID(), lineage, ZERO.lsn(), ZERO);
      var manifests = new AtomicGenerationManifestStore(root, initial);
      LogManager log =
          base == null
              ? LogManager.createNew(root, lineage, ZERO)
              : LogManager.openExisting(root, manifests.readActive());
      var wal = new WalService(new CommitClock(), log);
      var buffer = new BufferPool(new DiskManager(root), wal);
      var catalog = new CatalogManager(root);
      var serializer = new RecordSerializer();
      var heap = new HeapFile(root, buffer, serializer, catalog);
      var indexes = new IndexManager(heap, serializer);
      var records = new RecordStore(heap, buffer, serializer, indexes, catalog);
      var generations = new GenerationStore(root, manifests, buffer, catalog, indexes, records);
      var storage = new StorageManagerImpl(records, catalog, indexes);
      var storageRecovery = new StorageRecoveryAdapter(records, generations, indexes, buffer);
      var undo = new UndoManager(log, storageRecovery);
      return new RuntimeGraph(
          root, manifests, log, wal, catalog, storage, generations, storageRecovery, undo);
    }

    private HistoricalRecoveryManager createHistoricalManager() {
      return new HistoricalRecoveryManager(
          storageRecovery, history, storageRecovery, log, wal, manifests, coordinator);
    }

    private RecoveryInitializer initializer() {
      var crash =
          new CrashRecoveryManager(log, wal, manifests, storageRecovery, undo, storageRecovery);
      return new RecoveryInitializer(manifests, crash, coordinator);
    }

    private void insert(long id, long timestamp) {
      TransactionId tx = new TransactionId(id);
      wal.begin(tx, timestamp);
      PreparedWrite prepared =
          storage.prepareInsert(tx, "items", new Row(List.of(new IntValue((int) id))));
      var change =
          new ChangeRecord(
              tx, prepared.table(), prepared.rid(), prepared.before(), prepared.after());
      LogSequenceNumber lsn = wal.appendChange(change);
      storage.applyWrite(prepared, lsn);
      wal.commitDurably(tx);
    }

    private List<Integer> values() {
      var values = new java.util.ArrayList<Integer>();
      try (var cursor = storage.openTableScan("items", new TransactionId(99))) {
        for (var row = cursor.next(); row.isPresent(); row = cursor.next()) {
          values.add(((IntValue) row.get().row().values().getFirst()).value());
        }
      }
      values.sort(null);
      return values;
    }

    @Override
    public void close() {
      log.close();
    }
  }

  private static final class NoopCoordinator implements RecoveryCoordinator {
    @Override
    public MaintenanceGuard acquireMaintenance() {
      return () -> {};
    }

    @Override
    public long transactionTimestamp(TransactionId tx) {
      return 0;
    }

    @Override
    public void initializeRecoveredState(RecoveryHighWater highWater) {}
  }
}
