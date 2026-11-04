package dynamitedb.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.app.DbmsConfig;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.ProtocolType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubRuntimeGraphTest {
  @Test
  void concreteFacadesShareTheirCrossModuleDependencies(@TempDir Path directory) throws Exception {
    for (var protocol : ProtocolType.values()) {
      var runtime = runtime(directory.resolve(protocol.name()), protocol);
      var recovery = invoke(runtime, "recovery");
      var storage = invoke(runtime, "storage");
      var concurrency = invoke(runtime, "concurrency");
      var optimizer = invoke(runtime, "optimizer");
      var processor = invoke(runtime, "processor");
      var context = invoke(runtime, "recoveryContext");

      var wal = field(recovery, "wal");
      var undo = field(recovery, "rollback");
      var storageRecovery = field(field(recovery, "crash"), "storage");
      var records = field(storage, "records");
      var buffer = field(records, "buffer");
      var indexes = field(storage, "indexes");
      var catalog = field(storage, "catalog");
      var generationStore = field(storageRecovery, "generations");
      var manifests = field(generationStore, "manifests");
      var bridge = field(concurrency, "bridge");
      var registry = field(concurrency, "registry");
      var maintenance = field(concurrency, "maintenance");
      var catalogAdapter = field(optimizer, "binder");
      catalogAdapter = field(catalogAdapter, "catalog");

      assertEquals("dynamitedb.frm.WalService", wal.getClass().getName());
      assertEquals("dynamitedb.frm.UndoManager", undo.getClass().getName());
      assertEquals(
          "dynamitedb.sm.facade.StorageRecoveryAdapter", storageRecovery.getClass().getName());
      assertEquals(
          "dynamitedb.sm.catalog.StorageCatalogAdapter", catalogAdapter.getClass().getName());
      assertEquals("dynamitedb.app.StubRuntime", runtime.getClass().getName());
      assertSame(wal, field(buffer, "durability"));
      assertSame(wal, field(bridge, "wal"));
      assertSame(undo, field(bridge, "rollback"));
      assertSame(storageRecovery, field(field(recovery, "checkpoint"), "pages"));
      assertSame(storageRecovery, field(field(recovery, "historical"), "storage"));
      assertSame(storageRecovery, field(field(recovery, "historical"), "generations"));
      assertSame(records, field(storageRecovery, "records"));
      assertSame(generationStore, field(storageRecovery, "generations"));
      assertSame(indexes, field(storageRecovery, "indexes"));
      assertSame(buffer, field(storageRecovery, "buffer"));
      assertSame(records, field(generationStore, "records"));
      assertSame(indexes, field(generationStore, "indexes"));
      assertSame(buffer, field(generationStore, "buffer"));
      assertSame(catalog, field(generationStore, "catalog"));
      assertSame(manifests, field(field(recovery, "historical"), "manifests"));
      assertSame(manifests, field(field(recovery, "crash"), "manifests"));
      assertSame(manifests, field(generationStore, "manifests"));
      assertSame(registry, field(maintenance, "registry"));
      assertSame(catalogAdapter, field(field(optimizer, "cost"), "catalog"));
      assertSame(catalog, field(catalogAdapter, "catalog"));
      var rewrites = (List<?>) field(field(optimizer, "pipeline"), "rewrites");
      var accessPath =
          rewrites.stream()
              .filter(rewrite -> rewrite.getClass().getName().endsWith(".AccessPathOptimizer"))
              .findFirst()
              .orElseThrow();
      assertSame(catalogAdapter, field(accessPath, "catalog"));

      var protocolImpl = field(concurrency, "protocol");
      if (protocol == ProtocolType.STRICT_TIMESTAMP) {
        assertEquals(
            "dynamitedb.ccm.timestamp.TimestampOrderingProtocol",
            protocolImpl.getClass().getName());
        assertSame(registry, field(protocolImpl, "registry"));
      } else {
        assertEquals(
            "dynamitedb.ccm.protocol.Strict2PLProtocol", protocolImpl.getClass().getName());
        assertNotNull(field(protocolImpl, "locks"));
      }

      var coordinator = field(processor, "txCoordinator");
      assertSame(invoke(runtime, "optimizer"), field(processor, "optimizer"));
      assertSame(concurrency, field(coordinator, "tx"));
      assertSame(concurrency, field(coordinator, "access"));
      assertSame(catalogAdapter, field(coordinator, "catalog"));

      var execution = field(processor, "execution");
      var engine = capturedField(execution, "dynamitedb.qp.execution.ExecutionEngine");
      var translator = field(engine, "translator");
      var writer = field(engine, "writer");
      var writes = field(writer, "writes");
      var delete = field(writer, "delete");
      var evaluator = field(translator, "evaluator");
      assertSame(storage, field(translator, "reader"));
      assertSame(catalogAdapter, field(translator, "catalog"));
      assertSame(coordinator, field(translator, "coordinator"));
      assertSame(storage, field(writer, "store"));
      assertSame(storage, field(writer, "indexAdmin"));
      assertSame(coordinator, field(writer, "coordinator"));
      assertSame(translator, field(writer, "translator"));
      assertSame(evaluator, field(writer, "evaluator"));
      assertSame(concurrency, field(writes, "logger"));
      assertSame(storage, field(delete, "store"));
      assertSame(writes, field(delete, "writes"));
      assertSame(translator, field(delete, "translator"));
      assertSame(evaluator, field(delete, "evaluator"));
      assertSame(coordinator, field(delete, "coordinator"));
      assertSame(coordinator, field(field(engine, "transactionExecutor"), "coordinator"));

      assertSame(context, field(wal, "context"));
      assertSame(context, field(undo, "context"));
      assertSame(context, field(manifests, "context"));
      assertSame(context, field(field(recovery, "crash"), "context"));
      assertSame(context, field(field(recovery, "checkpoint"), "context"));
      assertSame(context, field(field(recovery, "historical"), "context"));
      assertEquals(protocol, invoke(concurrency, "activeProtocol"));
    }
  }

  @Test
  void stubListenerAcceptsAndReleasesLoopbackPortWithoutCreatingData(@TempDir Path directory)
      throws Exception {
    var data = directory.resolve("data");
    var runtime = runtime(data, ProtocolType.STRICT_2PL);
    var bootstrap = invoke(runtime, "bootstrap");
    assertFalse(Files.exists(data));

    invoke(
        bootstrap,
        "start",
        new Class<?>[] {DbmsConfig.class},
        new DbmsConfig(0, data, ProtocolType.STRICT_2PL));
    var port = (int) invoke(bootstrap, "port");
    try (var socket = new Socket()) {
      socket.connect(
          new InetSocketAddress("127.0.0.1", port), (int) Duration.ofSeconds(2).toMillis());
    }
    invoke(bootstrap, "shutdown");

    try (var released = new ServerSocket()) {
      released.bind(new InetSocketAddress("127.0.0.1", port));
    }
    assertFalse(Files.exists(data));
  }

  @Test
  void failedRecoveryDoesNotCreateOrBindListener(@TempDir Path directory) throws Exception {
    var data = directory.resolve("data");
    var runtime = runtime(data, ProtocolType.STRICT_2PL);
    var bootstrap = invoke(runtime, "bootstrap");
    var context = invoke(runtime, "recoveryContext");
    invoke(
        context,
        "failOn",
        new Class<?>[] {String.class, DbmsException.class},
        "RecoveryInitializer.openExisting",
        new DbmsException(ErrorCode.INVALID_STATE, "test failure"));

    var startup =
        assertThrows(
            InvocationTargetException.class,
            () ->
                invoke(
                    bootstrap,
                    "start",
                    new Class<?>[] {DbmsConfig.class},
                    new DbmsConfig(0, data, ProtocolType.STRICT_2PL)));
    assertInstanceOf(DbmsException.class, startup.getCause());
    assertNull(field(bootstrap, "server"));
    var portRead = assertThrows(InvocationTargetException.class, () -> invoke(bootstrap, "port"));
    assertInstanceOf(IllegalStateException.class, portRead.getCause());
    assertFalse(Files.exists(data));
  }

  private static Object runtime(Path data, ProtocolType protocol) throws Exception {
    return Class.forName("dynamitedb.app.StubRuntime")
        .getConstructor(DbmsConfig.class)
        .newInstance(new DbmsConfig(0, data, protocol));
  }

  private static Object invoke(Object target, String method) throws ReflectiveOperationException {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static Object invoke(
      Object target, String method, Class<?>[] parameterTypes, Object... arguments)
      throws ReflectiveOperationException {
    return target.getClass().getMethod(method, parameterTypes).invoke(target, arguments);
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object capturedField(Object target, String typeName)
      throws ReflectiveOperationException {
    var fields =
        Arrays.stream(target.getClass().getDeclaredFields())
            .filter(field -> field.getType().getName().equals(typeName))
            .toList();
    assertEquals(1, fields.size(), "Expected exactly one captured field of type " + typeName);
    fields.getFirst().setAccessible(true);
    return fields.getFirst().get(target);
  }
}
