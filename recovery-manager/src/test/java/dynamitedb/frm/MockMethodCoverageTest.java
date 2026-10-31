package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CheckpointService;
import dynamitedb.contracts.api.CrashRecovery;
import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.api.HistoricalRecoveryService;
import dynamitedb.contracts.api.HistoricalRecoveryStorage;
import dynamitedb.contracts.api.LogInspection;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.RecoveryPageStore;
import dynamitedb.contracts.api.RollbackService;
import dynamitedb.contracts.api.StorageGenerationAdmin;
import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.api.WalDurability;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryBoundary;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.recovery.RecoveryStage;
import dynamitedb.contracts.recovery.RecoveryTarget;
import dynamitedb.contracts.recovery.SealedGeneration;
import dynamitedb.contracts.recovery.SnapshotMetadata;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import dynamitedb.frm.mock.MockRecoveryEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

class MockMethodCoverageTest {
  @TempDir Path directory;

  @TestFactory
  Stream<DynamicTest> everyCanonicalPortAndServiceMethodIsCallable() throws Exception {
    List<String> rows;
    try (var reader =
        new BufferedReader(
            new InputStreamReader(
                getClass().getResourceAsStream("/frm-canonical-signatures.tsv"),
                StandardCharsets.UTF_8))) {
      rows = reader.lines().filter(s -> s.startsWith("method\t")).toList();
    }
    return rows.stream()
        .filter(
            row -> {
              var type = row.split("\t")[1];
              return type.startsWith("dynamitedb.frm.")
                  || type.matches(
                      ".*\\.(TransactionWal|RollbackService|WalDurability|CrashRecovery|CheckpointService|HistoricalRecoveryService|LogInspection|RecoveryCoordinator|MaintenanceGuard|RecoveryPageStore|HistoricalRecoveryStorage|StorageGenerationAdmin|GenerationManifestStore)$");
            })
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row,
                    () -> {
                      var root = directory.resolve("not-created");
                      var env = new MockRecoveryEnvironment(root, new MockRecoveryContext());
                      var parts = row.split("\t", -1);
                      var type = Class.forName(parts[1]);
                      var provider = providers(env).get(type);
                      assertNotNull(provider, type.getName());
                      var params =
                          parts.length < 5 || parts[4].isEmpty()
                              ? List.<String>of()
                              : Arrays.asList(parts[4].split("\\|"));
                      var method =
                          Arrays.stream(type.getMethods())
                              .filter(
                                  m ->
                                      m.getName().equals(parts[2])
                                          && Arrays.stream(m.getGenericParameterTypes())
                                              .map(java.lang.reflect.Type::getTypeName)
                                              .toList()
                                              .equals(params))
                              .findFirst()
                              .orElseThrow();
                      var values =
                          Arrays.stream(method.getParameterTypes())
                              .map(t -> argument(t, env))
                              .toArray();
                      var result = method.invoke(provider, values);
                      if (method.getReturnType() != void.class) {
                        assertNotNull(result);
                        if (result instanceof java.util.Iterator<?> iterator) {
                          while (iterator.hasNext()) {
                            assertNotNull(iterator.next());
                          }
                        }
                      }
                      assertFalse(Files.exists(root), "A mock must not create persistent storage");
                      assertTrue(!env.context().calls().isEmpty());
                    }));
  }

  static Map<Class<?>, Object> providers(MockRecoveryEnvironment e) {
    var p = new LinkedHashMap<Class<?>, Object>();
    for (var port :
        List.of(
            TransactionWal.class,
            RollbackService.class,
            WalDurability.class,
            CrashRecovery.class,
            CheckpointService.class,
            HistoricalRecoveryService.class,
            LogInspection.class)) {
      p.put(port, e.recovery());
    }
    p.put(RecoveryCoordinator.class, e.ports().coordinator());
    p.put(MaintenanceGuard.class, e.ports().coordinator().acquireMaintenance());
    p.put(RecoveryPageStore.class, e.ports().pages());
    p.put(HistoricalRecoveryStorage.class, e.ports().historicalStorage());
    p.put(StorageGenerationAdmin.class, e.ports().generations());
    p.put(GenerationManifestStore.class, e.manifests());
    for (var service :
        List.of(
            e.recovery(),
            e.initializer(),
            e.driver(),
            e.log(),
            e.wal(),
            e.file(),
            e.codec(),
            e.clock(),
            e.undo(),
            e.crash(),
            e.checkpoint(),
            e.historical(),
            e.catalog(),
            e.manifests())) {
      p.put(service.getClass(), service);
    }
    return p;
  }

  static Object argument(Class<?> type, MockRecoveryEnvironment e) {
    var c = e.context();
    if (type == TransactionId.class) return c.tx();
    if (type == ChangeRecord.class) return c.change();
    if (type == LogSequenceNumber.class) return c.lsn();
    if (type == GenerationManifest.class) return c.manifest();
    if (type == SnapshotMetadata.class) return c.snapshot();
    if (type == RecoveryHighWater.class) return c.highWater();
    if (type == RecoveryTarget.class)
      return new RecoveryTarget(Optional.of(c.commitTime()), Optional.empty());
    if (type == LogFilter.class) return new LogFilter(Optional.empty(), Optional.empty());
    if (type == RecoveryBoundary.class) return c.boundary();
    if (type == SealedGeneration.class) return c.sealed();
    if (type == RecoveryStage.class) return c.stage();
    if (type == RecordId.class) return c.rid();
    if (type == RecoveryMutation.class) return new RecoveryMutation(c.rid(), c.image());
    if (type == RecoveryState.class) return c.state();
    if (type == ChangeLogRecord.class || type == LogRecord.class) return c.record();
    if (type == ImmutableBytes.class) return c.frame();
    if (type == UUID.class) return c.lineage();
    if (type == Instant.class) return c.commitTime();
    if (type == Duration.class) return Duration.ofSeconds(1);
    if (type == long.class) return 1L;
    if (type == String.class) return "wal";
    throw new AssertionError("Missing test fixture for canonical parameter " + type);
  }
}
