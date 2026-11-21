package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.catalog.SampleDatabaseLoader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageStartupTest {
  private static final TransactionId TX = new TransactionId(1);

  @TempDir Path directory;

  @Test
  void sampleDatabaseLoadsThroughRuntimeAndSurvivesRestart() throws Exception {
    DbmsConfig config =
        new DbmsConfig(0, directory.resolve("data"), DbmsConfig.defaults().protocol());

    StubRuntime first = new StubRuntime(config);
    first.bootstrap().start(config);
    try {
      // StubRuntime intentionally keeps recovery mocked; production persistence is covered by
      // PersistentRuntimeTest.
      new SampleDatabaseLoader(first.storage(), first.concurrency(), first.concurrency())
          .loadIfAbsent(first.catalog());
      for (String table : List.of("student", "course", "enrollment")) {
        assertTrue(count(first, table) >= 50, table + " must hold the sample rows");
      }
      first.storage().createIndex("idx_student_id", "student", "student_id", IndexKind.HASH);
    } finally {
      first.bootstrap().shutdown();
    }
    int students = count(first, "student");
    // Flush manually because this fixture does not use the persistent FRM runtime.
    first.storageRecovery().flushDirtyThrough(new LogSequenceNumber(Long.MAX_VALUE));

    StubRuntime second = new StubRuntime(config);
    second.bootstrap().start(config);
    try {
      assertEquals(students, count(second, "student"), "restart must not reseed or lose rows");
      try (RowCursor cursor =
          second
              .storage()
              .openIndexScan(
                  "idx_student_id", new IndexPredicate(ComparisonOp.EQ, new IntValue(7)), TX)) {
        assertTrue(cursor.next().isPresent(), "index definition must survive restart");
      }
    } finally {
      second.bootstrap().shutdown();
    }
  }

  private static int count(StubRuntime runtime, String table) {
    int rows = 0;
    try (RowCursor cursor = runtime.storage().openTableScan(table, TX)) {
      while (cursor.next().isPresent()) {
        rows++;
      }
    }
    return rows;
  }
}
