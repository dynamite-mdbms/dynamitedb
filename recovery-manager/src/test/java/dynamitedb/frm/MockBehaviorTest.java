package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CheckpointService;
import dynamitedb.contracts.api.HistoricalRecoveryStorage;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.api.WalDurability;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.LogView;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.testing.TestReport;
import dynamitedb.contracts.testing.TestResult;
import dynamitedb.frm.mock.MockRecoveryContext;
import dynamitedb.frm.mock.MockRecoveryEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class MockBehaviorTest {
  @Test
  void facadeDelegatesAllSevenPortsToServices() {
    var e = MockRecoveryEnvironment.create();
    var c = e.context();
    var f = e.recovery();
    f.begin(c.tx(), 1);
    assertEquals(c.lsn(), f.appendChange(c.change()));
    f.commitDurably(c.tx());
    f.ensureDurable(c.lsn());
    f.rollbackCompletely(c.tx());
    f.recover();
    f.checkpoint();
    f.recoverTo(new RecoveryTarget(Optional.of(c.commitTime()), Optional.empty()));
    f.readLog(new LogFilter(Optional.empty(), Optional.empty()));
    assertEquals(
        List.of(
            "WalService.begin",
            "WalService.appendChange",
            "WalService.commitDurably",
            "WalService.ensureDurable",
            "LogManager.flush",
            "UndoManager.rollbackCompletely",
            "CrashRecoveryManager.recover",
            "CheckpointManager.checkpoint",
            "HistoricalRecoveryManager.recoverTo",
            "WalService.readLog",
            "LogManager.read"),
        c.calls());
  }

  @TestFactory
  Stream<DynamicTest> facadeDoesNotSwallowTypedFailures() {
    return List.of(
            "WalService.begin",
            "WalService.appendChange",
            "WalService.commitDurably",
            "LogManager.flush",
            "UndoManager.rollbackCompletely",
            "CrashRecoveryManager.recover",
            "CheckpointManager.checkpoint",
            "HistoricalRecoveryManager.recoverTo",
            "LogManager.read")
        .stream()
        .map(
            operation ->
                DynamicTest.dynamicTest(
                    operation,
                    () -> {
                      var e = MockRecoveryEnvironment.create();
                      var c = e.context();
                      var f = e.recovery();
                      var failure =
                          new DbmsException(ErrorCode.RECOVERY_FAILURE, "injected mock failure");
                      c.failOn(operation, failure);
                      var thrown =
                          assertThrows(
                              DbmsException.class,
                              () -> {
                                switch (operation) {
                                  case "WalService.begin" -> f.begin(c.tx(), 1);
                                  case "WalService.appendChange" -> f.appendChange(c.change());
                                  case "WalService.commitDurably" -> f.commitDurably(c.tx());
                                  case "LogManager.flush" -> f.ensureDurable(c.lsn());
                                  case "UndoManager.rollbackCompletely" ->
                                      f.rollbackCompletely(c.tx());
                                  case "CrashRecoveryManager.recover" -> f.recover();
                                  case "CheckpointManager.checkpoint" -> f.checkpoint();
                                  case "HistoricalRecoveryManager.recoverTo" ->
                                      f.recoverTo(
                                          new RecoveryTarget(
                                              Optional.empty(), Optional.of(c.tx())));
                                  case "LogManager.read" ->
                                      f.readLog(new LogFilter(Optional.empty(), Optional.empty()));
                                  default -> throw new AssertionError(operation);
                                }
                              });
                      assertSame(failure, thrown);
                      assertEquals(ErrorCode.RECOVERY_FAILURE, thrown.getCode());
                    }));
  }

  @Test
  void existingDatabaseFailureDoesNotFallBackToNewDatabaseInitialization() {
    var e = MockRecoveryEnvironment.create();
    var failure = new DbmsException(ErrorCode.CORRUPT_LOG, "injected corrupt WAL");
    e.context().failOn("CrashRecoveryManager.recover", failure);
    assertSame(failure, assertThrows(DbmsException.class, () -> e.initializer().openExisting()));
    assertEquals(
        List.of(
            "RecoveryInitializer.openExisting",
            "AtomicGenerationManifestStore.readActive",
            "CrashRecoveryManager.recover"),
        e.context().calls());
  }

  @Test
  void generationHandleRefreshIsNotAnotherPublicationPoint() {
    var e = MockRecoveryEnvironment.create();
    var c = e.context();
    var replacement = new GenerationManifest(new UUID(7, 8), c.lineage(), c.lsn(), c.highWater());
    e.ports().generations().switchToPublishedGeneration(replacement);
    assertEquals(c.manifest(), e.manifests().readActive());
    e.manifests().publishAtomically(replacement);
    assertEquals(replacement, e.ports().generations().readActiveManifest());
  }

  @Test
  void immutableBytesAndImagesUseDefensiveCopyingAndValueEquality() {
    byte[] input = {4, 2};
    var a = ImmutableBytes.copyOf(input);
    input[0] = 9;
    byte[] output = a.copy();
    output[0] = 8;
    var b = ImmutableBytes.copyOf(new byte[] {4, 2});
    assertEquals(b, a);
    assertEquals(b.hashCode(), a.hashCode());
    assertEquals(new RecordImage(true, b), new RecordImage(true, a));
    assertNotEquals(
        new RecordImage(false, ImmutableBytes.copyOf(new byte[0])),
        new RecordImage(true, ImmutableBytes.copyOf(new byte[0])));
    assertThrows(IllegalArgumentException.class, () -> new RecordImage(false, a));
  }

  @Test
  void resultCollectionsAreImmutableSnapshots() {
    var lines = new ArrayList<>(List.of("first"));
    var view = new LogView(lines);
    lines.add("second");
    assertEquals(List.of("first"), view.lines());
    assertThrows(UnsupportedOperationException.class, () -> view.lines().add("invalid"));
    var results = new ArrayList<>(List.of(new TestResult(true, "mock")));
    var report = new TestReport(results);
    results.clear();
    assertEquals(1, report.results().size());
    assertThrows(UnsupportedOperationException.class, () -> report.results().clear());
    var c = new MockRecoveryContext();
    var winners = new HashSet<>(List.of(c.tx()));
    var state = new RecoveryState(winners, new HashSet<>(), c.lsn(), c.highWater());
    winners.clear();
    assertTrue(state.winners().contains(c.tx()));
    assertThrows(UnsupportedOperationException.class, () -> state.winners().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryState(state.winners(), state.winners(), c.lsn(), c.highWater()));
  }

  @Test
  void invalidTargetsAndEventMetadataAreRejected() {
    var c = new MockRecoveryContext();
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryTarget(Optional.empty(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryTarget(Optional.of(Instant.EPOCH), Optional.of(c.tx())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TransactionEventLogRecord(
                c.lsn(),
                c.tx(),
                Optional.empty(),
                c.lineage(),
                TxEvent.BEGIN,
                Optional.empty(),
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TransactionEventLogRecord(
                c.lsn(),
                c.tx(),
                Optional.empty(),
                c.lineage(),
                TxEvent.COMMIT,
                Optional.of(1L),
                Optional.of(Instant.EPOCH)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TransactionEventLogRecord(
                c.lsn(),
                c.tx(),
                Optional.empty(),
                c.lineage(),
                TxEvent.ABORT_COMPLETE,
                Optional.empty(),
                Optional.of(Instant.EPOCH)));
    assertThrows(
        IllegalArgumentException.class,
        () -> MockRecoveryEnvironment.create().checkpoint().startPeriodic(Duration.ZERO));
  }

  @Test
  void oneCanonicalLsnIsUsedAcrossCheckpointAndHistoricalContracts() throws Exception {
    assertEquals(
        LogSequenceNumber.class, CheckpointResult.class.getRecordComponents()[0].getType());
    assertEquals(
        LogSequenceNumber.class, GenerationManifest.class.getRecordComponents()[2].getType());
    assertEquals(
        LogSequenceNumber.class, RecoveryBoundary.class.getRecordComponents()[0].getType());
    assertEquals(
        CheckpointResult.class, CheckpointService.class.getMethod("checkpoint").getReturnType());
    assertEquals(
        LogSequenceNumber.class,
        TransactionWal.class.getMethod("appendChange", ChangeRecord.class).getReturnType());
    assertEquals(
        void.class,
        WalDurability.class.getMethod("ensureDurable", LogSequenceNumber.class).getReturnType());
    assertEquals(
        void.class,
        RecoveryPageStore.class
            .getMethod("applyRecovery", RecoveryMutation.class, LogSequenceNumber.class)
            .getReturnType());
    assertEquals(
        void.class,
        HistoricalRecoveryStorage.class
            .getMethod(
                "replayInStage",
                RecoveryStage.class,
                RecoveryMutation.class,
                LogSequenceNumber.class)
            .getReturnType());
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("dynamitedb.contracts.LogSequenceNumber"));
  }

  @Test
  void unknownFramesAndInjectedFailuresRemainTyped() {
    var e = MockRecoveryEnvironment.create();
    var bad = ImmutableBytes.copyOf(new byte[] {0});
    assertFalse(e.codec().verify(bad));
    assertEquals(
        ErrorCode.CORRUPT_LOG,
        assertThrows(DbmsException.class, () -> e.codec().decode(bad)).getCode());
  }

  @Test
  void historicalFixtureConstructorsRemainConfigurable() {
    var c =
        new MockRecoveryContext(
            new LogSequenceNumber(19),
            Instant.parse("2026-01-01T00:00:00Z"),
            RecoveryStatus.FAILED);
    var manager = new HistoricalRecoveryManager(c.result(), c.boundary());
    var target = new RecoveryTarget(Optional.empty(), Optional.of(c.tx()));
    assertEquals(c.result(), manager.recoverTo(target));
    assertEquals(c.boundary(), manager.resolveBoundary(target));
    var catalog = new HistoryCatalog(c.snapshot(), c.manifest());
    assertEquals(c.snapshot(), catalog.getBaseSnapshot(target));
    assertEquals(c.manifest(), catalog.buildManifest(c.sealed(), c.lineage(), c.highWater()));
  }

  @Test
  void driverDistinguishesMockSuccessUnknownScenarioAndFailure() {
    var e = MockRecoveryEnvironment.create();
    var report = e.driver().runAll();
    assertEquals(8, report.results().size());
    assertTrue(
        report.results().stream()
            .allMatch(r -> r.passed() && r.message().startsWith("MOCK ONLY:")));
    assertFalse(e.driver().runScenario("not-a-scenario").passed());
    var failed =
        new MockRecoveryEnvironment(
            Path.of("unused"),
            new MockRecoveryContext(
                new LogSequenceNumber(1), Instant.EPOCH, RecoveryStatus.FAILED));
    assertFalse(failed.driver().runScenario("startup").passed());
    e.context()
        .failOn("LogManager.flush", new DbmsException(ErrorCode.WAL_FAILURE, "force failed"));
    assertFalse(e.driver().runScenario("wal").passed());
  }

  @Test
  void standaloneDriverReturnsNonzeroForAnUnknownScenario() throws Exception {
    String classpath =
        Path.of(
                RecoveryTestDriver.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            + File.pathSeparator
            + Path.of(
                LogSequenceNumber.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                RecoveryTestDriver.class.getName(),
                "unknown")
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    assertTrue(finished);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(1, process.exitValue());
    assertTrue(output.contains("FAIL MOCK ONLY:"));
  }
}
