package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.HistoricalRecoveryStorage;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalRecoveryManagerTest {
  private static final Instant T10 = Instant.parse("2026-01-01T00:00:10Z");
  private static final Instant T20 = Instant.parse("2026-01-01T00:00:20Z");
  private static final Instant T30 = Instant.parse("2026-01-01T00:00:30Z");

  @TempDir Path directory;

  @Test
  void timeTargetReplaysOnlyCommitsThroughTimeAndBreaksTiesByLsn() {
    Fixture fixture = new Fixture(directory);

    RecoveryResult result =
        fixture.manager.recoverTo(new RecoveryTarget(Optional.of(T20), Optional.empty()));

    assertEquals(RecoveryStatus.SUCCESS, result.status());
    assertEquals(new LogSequenceNumber(11), fixture.manifests.readActive().boundaryLsn());
    assertEquals(List.of(1, 3, 4), fixture.storage.replayedValues);
    assertEquals(1, fixture.storage.materializedSnapshots.size());
    assertEquals(
        fixture.manifests.readActive().activeId(),
        fixture.storage.materializedSnapshots.getFirst().id());
    assertEquals(fixture.highWater, fixture.coordinator.initializedHighWater);
    assertTrue(fixture.coordinator.guardClosed);
  }

  @Test
  void transactionTargetUsesItsExactCommitLsn() {
    Fixture fixture = new Fixture(directory);

    fixture.manager.recoverTo(
        new RecoveryTarget(Optional.empty(), Optional.of(new TransactionId(3))));

    assertEquals(new LogSequenceNumber(8), fixture.manifests.readActive().boundaryLsn());
    assertEquals(List.of(1, 3), fixture.storage.replayedValues);
  }

  @Test
  void abortedOrUnknownTransactionIsRejectedBeforeStagingOrPublication() {
    Fixture fixture = new Fixture(directory);
    GenerationManifest before = fixture.manifests.readActive();

    DbmsException failure =
        assertThrows(
            DbmsException.class,
            () ->
                fixture.manager.recoverTo(
                    new RecoveryTarget(Optional.empty(), Optional.of(new TransactionId(5)))));

    assertEquals(ErrorCode.RECOVERY_FAILURE, failure.getCode());
    assertEquals(0, fixture.storage.stageCount);
    assertEquals(before, fixture.manifests.readActive());
    assertTrue(fixture.coordinator.guardClosed);
  }

  @Test
  void publishedManifestCanBeReadByANewStoreInstance() {
    Fixture fixture = new Fixture(directory);
    fixture.manager.recoverTo(new RecoveryTarget(Optional.of(T20), Optional.empty()));
    GenerationManifest published = fixture.manifests.readActive();
    GenerationManifest differentInitial = fixture.newManifest();
    GenerationManifestStore restarted =
        new AtomicGenerationManifestStore(
            directory.resolve("manifest"), differentInitial, fixture.context);

    assertEquals(published, restarted.readActive());
  }

  private static TransactionEventLogRecord begin(
      long lsn, TransactionId tx, UUID lineage, long startTimestamp) {
    return new TransactionEventLogRecord(
        new LogSequenceNumber(lsn),
        tx,
        Optional.empty(),
        lineage,
        TxEvent.BEGIN,
        Optional.of(startTimestamp),
        Optional.empty());
  }

  private static TransactionEventLogRecord commit(
      long lsn, TransactionId tx, UUID lineage, Instant commitTime) {
    return new TransactionEventLogRecord(
        new LogSequenceNumber(lsn),
        tx,
        Optional.empty(),
        lineage,
        TxEvent.COMMIT,
        Optional.empty(),
        Optional.of(commitTime));
  }

  private static TransactionEventLogRecord abort(long lsn, TransactionId tx, UUID lineage) {
    return new TransactionEventLogRecord(
        new LogSequenceNumber(lsn),
        tx,
        Optional.empty(),
        lineage,
        TxEvent.ABORT_COMPLETE,
        Optional.empty(),
        Optional.empty());
  }

  private static ChangeLogRecord change(long lsn, TransactionId tx, UUID lineage, int value) {
    ChangeRecord change =
        new ChangeRecord(
            tx,
            "items",
            new RecordId(1, 1, value, 1),
            RecordImage.absent(),
            new RecordImage(true, ImmutableBytes.copyOf(new byte[] {(byte) value})));
    return new ChangeLogRecord(new LogSequenceNumber(lsn), Optional.empty(), lineage, change);
  }

  private static final class Fixture {
    private final MockRecoveryContext context = new MockRecoveryContext();
    private final UUID lineage = context.lineage();
    private final RecoveryHighWater highWater =
        new RecoveryHighWater(
            new LogSequenceNumber(15), new TransactionId(5), 15, Optional.of(T30));
    private final SnapshotMetadata snapshot =
        new SnapshotMetadata(UUID.randomUUID(), lineage, new LogSequenceNumber(0), Instant.EPOCH);
    private final AtomicGenerationManifestStore manifests;
    private final LogManager log;
    private final RecordingStorage storage = new RecordingStorage();
    private final RecordingCoordinator coordinator = new RecordingCoordinator();
    private final HistoryCatalog catalog;
    private final HistoricalRecoveryManager manager;

    private Fixture(Path directory) {
      List<LogRecord> records =
          List.of(
              begin(1, new TransactionId(1), lineage, 1),
              change(2, new TransactionId(1), lineage, 1),
              commit(3, new TransactionId(1), lineage, T10),
              begin(4, new TransactionId(2), lineage, 2),
              change(5, new TransactionId(2), lineage, 2),
              begin(6, new TransactionId(3), lineage, 3),
              change(7, new TransactionId(3), lineage, 3),
              commit(8, new TransactionId(3), lineage, T20),
              begin(9, new TransactionId(4), lineage, 4),
              change(10, new TransactionId(4), lineage, 4),
              commit(11, new TransactionId(4), lineage, T20),
              commit(12, new TransactionId(2), lineage, T30),
              begin(13, new TransactionId(5), lineage, 5),
              change(14, new TransactionId(5), lineage, 5),
              abort(15, new TransactionId(5), lineage));
      GenerationManifest initial = newManifest();
      manifests =
          new AtomicGenerationManifestStore(directory.resolve("manifest"), initial, context);
      log =
          new LogManager(
              directory,
              lineage,
              new LogFile(context),
              new LogRecordCodec(context),
              context,
              records,
              highWater);
      catalog = new HistoryCatalog(manifests, log, List.of(snapshot), context);
      manager =
          new HistoricalRecoveryManager(
              storage,
              catalog,
              new RecordingGenerationAdmin(manifests),
              log,
              new WalService(new CommitClock(context), log, context),
              manifests,
              coordinator,
              context);
    }

    private GenerationManifest newManifest() {
      return new GenerationManifest(
          UUID.randomUUID(), lineage, new LogSequenceNumber(0), highWater);
    }
  }

  private static final class RecordingStorage implements HistoricalRecoveryStorage {
    private final List<Integer> replayedValues = new ArrayList<>();
    private final List<SnapshotMetadata> materializedSnapshots = new ArrayList<>();
    private int stageCount;

    @Override
    public SnapshotMetadata captureSnapshot(RecoveryBoundary boundary) {
      throw new AssertionError("Recovery should use the retained base snapshot");
    }

    @Override
    public RecoveryStage createStage(SnapshotMetadata snapshot, UUID id) {
      stageCount++;
      return new RecoveryStage(id);
    }

    @Override
    public void replayInStage(RecoveryStage stage, RecoveryMutation change, LogSequenceNumber lsn) {
      replayedValues.add((int) change.targetImage().encoded().copy()[0]);
    }

    @Override
    public void rebuildStageIndexes(RecoveryStage stage) {}

    @Override
    public SealedGeneration sealStage(RecoveryStage stage, RecoveryBoundary boundary) {
      return new SealedGeneration(stage.id(), boundary);
    }

    @Override
    public void materializeSnapshot(SealedGeneration generation, SnapshotMetadata snapshot) {
      assertEquals(generation.id(), snapshot.id());
      assertTrue(snapshot.baseLsn().value() >= generation.boundary().inclusiveLsn().value());
      materializedSnapshots.add(snapshot);
    }
  }

  private static final class RecordingCoordinator implements RecoveryCoordinator {
    private boolean guardClosed;
    private RecoveryHighWater initializedHighWater;

    @Override
    public MaintenanceGuard acquireMaintenance() {
      guardClosed = false;
      return () -> guardClosed = true;
    }

    @Override
    public long transactionTimestamp(TransactionId tx) {
      return 0;
    }

    @Override
    public void initializeRecoveredState(RecoveryHighWater highWater) {
      initializedHighWater = highWater;
    }
  }

  private static final class RecordingGenerationAdmin implements StorageGenerationAdmin {
    private final GenerationManifestStore manifests;

    private RecordingGenerationAdmin(GenerationManifestStore manifests) {
      this.manifests = manifests;
    }

    @Override
    public GenerationManifest readActiveManifest() {
      return manifests.readActive();
    }

    @Override
    public void switchToPublishedGeneration(GenerationManifest manifest) {
      assertNotNull(manifest);
    }
  }
}
