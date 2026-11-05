package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubRuntimeTest {
  @Test
  void successfulMockRecoveryInitializesCcmBeforeServingTransactions(@TempDir Path directory)
      throws Exception {
    var config = new DbmsConfig(0, directory, ProtocolType.STRICT_2PL);
    var runtime = new StubRuntime(config);
    assertThrows(DbmsException.class, runtime.concurrency()::begin);
    try {
      runtime.bootstrap().start(config);
      var tx = runtime.concurrency().begin();
      assertTrue(tx.value() > runtime.recoveryContext().highWater().maxTx().value());
      assertTrue(
          runtime.concurrency().transactionTimestamp(tx)
              > runtime.recoveryContext().highWater().maxTimestamp());
      runtime.concurrency().commit(tx);
    } finally {
      runtime.bootstrap().shutdown();
    }
  }

  @Test
  void failedMockRecoveryKeepsCcmAndListenerClosed(@TempDir Path directory) {
    var config = new DbmsConfig(0, directory, ProtocolType.STRICT_2PL);
    var runtime = new StubRuntime(config);
    runtime
        .recoveryContext()
        .failOn(
            "CrashRecoveryManager.recover",
            new DbmsException(ErrorCode.RECOVERY_FAILURE, "recovery gagal"));

    assertEquals(
        ErrorCode.RECOVERY_FAILURE,
        assertThrows(DbmsException.class, () -> runtime.bootstrap().start(config)).getCode());
    assertThrows(DbmsException.class, runtime.concurrency()::begin);
    assertThrows(IllegalStateException.class, runtime.bootstrap()::port);
  }

  @Test
  void composesActualModuleFacadesWithoutInitializationSideEffects(@TempDir Path directory) {
    Path data = directory.resolve("data");
    DbmsConfig defaults = DbmsConfig.defaults();
    StubRuntime runtime =
        new StubRuntime(new DbmsConfig(defaults.port(), data, defaults.protocol()));

    assertNotNull(runtime.processor());
    assertNotNull(runtime.optimizer());
    assertNotNull(runtime.storage());
    assertNotNull(runtime.concurrency());
    assertNotNull(runtime.recovery());
    assertEquals(0, runtime.recoveryContext().calls().size());
    assertFalse(data.toFile().exists());
  }
}
