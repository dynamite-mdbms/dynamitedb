package dynamitedb.sm.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.sm.serialization.RecordSerializer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleDatabaseLoaderTest {
  @TempDir Path directory;

  @Test
  void seedsRelatedTablesThroughLoggedWritesAndDoesNotRepeatAfterRestart() throws Exception {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.loader().loadIfAbsent(catalog);
    assertEquals(List.of("course", "enrollment", "student"), catalog.listTables());
    assertEquals(1, ports.begins);
    assertEquals(1, ports.commits);
    assertEquals(0, ports.aborts);
    assertEquals(200, ports.logged.size());
    assertEquals(200, ports.applied.size());
    assertEquals(50, ports.rows.get("student").size());
    assertEquals(50, ports.rows.get("course").size());
    assertEquals(100, ports.rows.get("enrollment").size());
    var studentIds = new HashSet<Integer>();
    var courseIds = new HashSet<Integer>();
    ports
        .rows
        .get("student")
        .forEach(row -> studentIds.add(((IntValue) row.values().getFirst()).value()));
    ports
        .rows
        .get("course")
        .forEach(row -> courseIds.add(((IntValue) row.values().getFirst()).value()));
    for (Row row : ports.rows.get("enrollment")) {
      assertTrue(studentIds.contains(((IntValue) row.values().get(0)).value()));
      assertTrue(courseIds.contains(((IntValue) row.values().get(1)).value()));
    }
    var dataTypes = new HashSet<DataType>();
    for (String table : catalog.listTables()) {
      catalog.getSchema(table).columns().forEach(column -> dataTypes.add(column.dataType()));
      assertEquals(ports.rows.get(table).size(), catalog.getStatistics(table).rowCount());
      assertTrue(catalog.getStatistics(table).averageRowBytes() > 0);
    }
    assertEquals(new HashSet<>(List.of(DataType.values())), dataTypes);
    byte[] before = Files.readAllBytes(directory.resolve("catalog.db"));
    ports.loader().loadIfAbsent(new CatalogManager(directory));
    new SampleDatabaseLoader().loadIfAbsent(new CatalogManager(directory));
    assertEquals(1, ports.begins);
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.db")));
  }

  @Test
  void existingUserDatabaseIsLeftAloneEvenWhenSampleTablesAreMissing() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable(
        "user_table",
        new Schema(List.of(new Column("user_table", "id", DataType.INTEGER, 0, false))));
    byte[] before = Files.readAllBytes(directory.resolve("catalog.db"));
    var ports = new RecordingPorts(catalog);
    ports.loader().loadIfAbsent(catalog);
    assertEquals(0, ports.begins);
    assertEquals(List.of("user_table"), catalog.listTables());
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.db")));
  }

  @Test
  void storageWithoutCatalogIsNotTreatedAsANewDatabase() throws Exception {
    byte[] existing = {0, 1, 2, 3};
    Files.write(directory.resolve("pages.db"), existing);
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog));
    assertFalse(Files.exists(directory.resolve("catalog.db")));
    assertEquals(0, ports.begins);
    assertArrayEquals(existing, Files.readAllBytes(directory.resolve("pages.db")));
  }

  @Test
  void missingWritePortsCannotProduceAnApparentlyInitializedDatabase() {
    var catalog = new CatalogManager(directory);
    assertEquals(
        ErrorCode.INVALID_STATE,
        assertThrows(DbmsException.class, () -> new SampleDatabaseLoader().loadIfAbsent(catalog))
            .code);
    assertFalse(Files.exists(directory.resolve("catalog.db")));
  }

  @Test
  void walFailureCancelsReservationAbortsAndAllowsRetryAfterRestart() {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.failWal = true;
    assertEquals(
        ErrorCode.WAL_FAILURE,
        assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog)).code);
    assertEquals(1, ports.cancels);
    assertEquals(1, ports.aborts);
    assertEquals(0, ports.commits);
    assertTrue(ports.applied.isEmpty());
    var reopened = new CatalogManager(directory);
    var retry = new RecordingPorts(reopened);
    retry.loader().loadIfAbsent(reopened);
    assertEquals(200, retry.applied.size());
  }

  @Test
  void applyFailureAfterLoggingUsesTransactionAbortWithoutCancellingLoggedWrite() {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.failApply = true;
    assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog));
    assertEquals(1, ports.logged.size());
    assertEquals(0, ports.cancels);
    assertEquals(1, ports.aborts);
    assertEquals(0, ports.commits);
  }

  @Test
  void commitFailureDoesNotPublishACompletedSampleDatabase() {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.failCommit = true;
    assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog));
    assertEquals(1, ports.aborts);
    assertFalse(catalog.hasDatabase());
    var reopened = new CatalogManager(directory);
    var retry = new RecordingPorts(reopened);
    retry.loader().loadIfAbsent(reopened);
    assertEquals(200, retry.applied.size());
  }

  @Test
  void transactionBeginFailureCanBeRetriedAfterRestart() {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.failBegin = true;
    assertEquals(
        ErrorCode.WAL_FAILURE,
        assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog)).code);
    assertTrue(ports.applied.isEmpty());
    var reopened = new CatalogManager(directory);
    var retry = new RecordingPorts(reopened);
    retry.loader().loadIfAbsent(reopened);
    assertEquals(200, retry.applied.size());
  }

  @Test
  void committedSeedCanBeFinalizedAfterCrashBeforeCatalogCompletion() {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.crashAfterCommit = true;
    assertThrows(AssertionError.class, () -> ports.loader().loadIfAbsent(catalog));
    assertEquals(1, ports.commits);
    assertEquals(0, ports.aborts);
    // The catalog can be stale after recovery; the recovered heap must decide completeness.
    catalog.listTables().forEach(table -> catalog.refreshStatistics(table, List.of()));
    var reopened = new CatalogManager(directory);
    var retry = new RecordingPorts(reopened);
    ports.rows.forEach((table, rows) -> retry.rows.put(table, new ArrayList<>(rows)));
    retry.loader().loadIfAbsent(reopened);
    assertTrue(retry.applied.isEmpty());
    assertEquals(3, retry.closedScans);
    assertEquals(100, reopened.getStatistics("enrollment").rowCount());
    assertTrue(new CatalogManager(directory).hasDatabase());
  }

  @Test
  void recoveredEmptyLegacyAttemptCanBeRetriedWithoutChangingTableIdentities() throws Exception {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.crashAfterWrites = 3;
    assertThrows(AssertionError.class, () -> ports.loader().loadIfAbsent(catalog));
    var ids = new HashMap<String, Long>();
    catalog.listTables().forEach(table -> ids.put(table, catalog.getTableId(table)));
    rewriteAsVersionOne();
    var reopened = new CatalogManager(directory);
    // Startup recovery has undone the loser transaction, while cached statistics still say 3 rows.
    var retry = new RecordingPorts(reopened);
    retry.loader().loadIfAbsent(reopened);
    assertEquals(3, retry.closedScans);
    assertEquals(200, retry.applied.size());
    assertEquals(50, reopened.getStatistics("student").rowCount());
    ids.forEach((table, id) -> assertEquals(id.longValue(), reopened.getTableId(table)));
    assertEquals(2, ByteBuffer.wrap(Files.readAllBytes(directory.resolve("catalog.db"))).getInt(4));
  }

  @Test
  void partialRecoveredSeedIsPreservedAndNeverReseeded() throws Exception {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.crashAfterWrites = 3;
    assertThrows(AssertionError.class, () -> ports.loader().loadIfAbsent(catalog));
    byte[] before = Files.readAllBytes(directory.resolve("catalog.db"));
    var reopened = new CatalogManager(directory);
    var retry = new RecordingPorts(reopened);
    ports.rows.forEach((table, rows) -> retry.rows.put(table, new ArrayList<>(rows)));
    assertEquals(
        ErrorCode.STORAGE_FAILURE,
        assertThrows(DbmsException.class, () -> retry.loader().loadIfAbsent(reopened)).code);
    assertTrue(retry.applied.isEmpty());
    assertEquals(3, retry.rows.get("student").size());
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.db")));
  }

  @Test
  void duplicateRecoveredRowsAreNotMistakenForACompletedSeed() throws Exception {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.crashAfterCommit = true;
    assertThrows(AssertionError.class, () -> ports.loader().loadIfAbsent(catalog));
    var reopened = new CatalogManager(directory);
    var retry = new RecordingPorts(reopened);
    ports.rows.forEach((table, rows) -> retry.rows.put(table, new ArrayList<>(rows)));
    retry.rows.get("student").set(1, retry.rows.get("student").getFirst());
    byte[] before = Files.readAllBytes(directory.resolve("catalog.db"));
    assertThrows(DbmsException.class, () -> retry.loader().loadIfAbsent(reopened));
    assertTrue(retry.applied.isEmpty());
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.db")));
  }

  @Test
  void failedAbortRequiresHeapRecoveryBeforeRetry() throws Exception {
    var catalog = new CatalogManager(directory);
    var ports = new RecordingPorts(catalog);
    ports.failApply = true;
    ports.failAbort = true;
    var error = assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog));
    assertEquals(1, error.getSuppressed().length);
    assertTrue(catalog.requiresSampleRecovery());
    var reopened = new CatalogManager(directory);
    var retry = new RecordingPorts(reopened);
    retry.loader().loadIfAbsent(reopened);
    assertEquals(3, retry.closedScans);
    assertEquals(200, retry.applied.size());
  }

  @Test
  void freshRecoveryMetadataCanBeValidatedBeforeCreatingTheCatalog() throws Exception {
    Path wal = directory.resolve("recovery.wal");
    Path manifest = directory.resolve("generation.manifest");
    Files.writeString(wal, "validated fresh recovery metadata");
    Files.writeString(manifest, "validated fresh recovery metadata");
    var catalog = new CatalogManager(directory, null, this::isFreshRecoveryMetadata);
    var ports = new RecordingPorts(catalog);
    ports.loader().loadIfAbsent(catalog);
    assertEquals(200, ports.applied.size());
    assertEquals("validated fresh recovery metadata", Files.readString(wal));
    assertEquals("validated fresh recovery metadata", Files.readString(manifest));
  }

  @Test
  void namesAloneDoNotAuthorizeSeedingOverOldRecoveryData() throws Exception {
    Path wal = directory.resolve("recovery.wal");
    Files.writeString(wal, "existing transaction history");
    var catalog = new CatalogManager(directory, null, this::isFreshRecoveryMetadata);
    var ports = new RecordingPorts(catalog);
    assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog));
    assertFalse(Files.exists(directory.resolve("catalog.db")));
    assertEquals("existing transaction history", Files.readString(wal));
  }

  @Test
  void unrecognizedFilesArePreservedAlongsideApprovedRecoveryMetadata() throws Exception {
    Files.writeString(directory.resolve("recovery.wal"), "validated fresh recovery metadata");
    Files.writeString(directory.resolve("user-data"), "legitimate data");
    var catalog = new CatalogManager(directory, null, this::isFreshRecoveryMetadata);
    var ports = new RecordingPorts(catalog);
    assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog));
    assertFalse(Files.exists(directory.resolve("catalog.db")));
    assertEquals("legitimate data", Files.readString(directory.resolve("user-data")));
  }

  @Test
  void heapStorageCannotBeApprovedAsFreshRecoveryMetadata() throws Exception {
    Files.writeString(directory.resolve("pages.db"), "existing rows");
    var catalog = new CatalogManager(directory, null, entry -> true);
    var ports = new RecordingPorts(catalog);
    assertThrows(DbmsException.class, () -> ports.loader().loadIfAbsent(catalog));
    assertFalse(Files.exists(directory.resolve("catalog.db")));
    assertEquals("existing rows", Files.readString(directory.resolve("pages.db")));
  }

  private boolean isFreshRecoveryMetadata(Path entry) {
    try {
      return (entry.getFileName().toString().equals("recovery.wal")
              || entry.getFileName().toString().equals("generation.manifest"))
          && Files.readString(entry).equals("validated fresh recovery metadata");
    } catch (IOException e) {
      return false;
    }
  }

  private void rewriteAsVersionOne() throws IOException {
    Path file = directory.resolve("catalog.db");
    byte[] bytes = Files.readAllBytes(file);
    ByteBuffer.wrap(bytes).putInt(4, 1);
    var checksum = new CRC32();
    checksum.update(bytes, 0, bytes.length - Long.BYTES);
    ByteBuffer.wrap(bytes).putLong(bytes.length - Long.BYTES, checksum.getValue());
    Files.write(file, bytes);
  }

  @Test
  void corruptCatalogIsNeverReplacedBySampleInitialization() throws Exception {
    byte[] damaged = {9, 8, 7};
    Files.write(directory.resolve("catalog.db"), damaged);
    assertThrows(DbmsException.class, () -> new CatalogManager(directory));
    assertArrayEquals(damaged, Files.readAllBytes(directory.resolve("catalog.db")));
  }

  /** Contract test double: applies real serialized images and models SM1's statistics callback. */
  private static final class RecordingPorts
      implements WriteStore, TupleReader, ChangeLogger, TransactionManager {
    private final CatalogManager catalog;
    private final RecordSerializer serializer = new RecordSerializer();
    private final Map<String, List<Row>> rows = new HashMap<>();
    private final Map<WriteReservationId, LogSequenceNumber> logged = new HashMap<>();
    private final List<PreparedWrite> applied = new ArrayList<>();
    private final Map<TransactionId, TransactionStatus> statuses = new HashMap<>();
    private int begins;
    private int commits;
    private int aborts;
    private int cancels;
    private boolean failWal;
    private boolean failApply;
    private boolean failCommit;
    private boolean failBegin;
    private boolean crashAfterCommit;
    private boolean failAbort;
    private int crashAfterWrites = -1;
    private int closedScans;

    private RecordingPorts(CatalogManager catalog) {
      this.catalog = catalog;
    }

    private SampleDatabaseLoader loader() {
      return new SampleDatabaseLoader(this, this, this);
    }

    @Override
    public PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
      var tableRows = rows.computeIfAbsent(table, key -> new ArrayList<>());
      return new PreparedWrite(
          tx,
          table,
          new RecordId(catalog.getTableId(table), 0, tableRows.size(), 0),
          RecordImage.absent(),
          serializer.encode(row, catalog.getSchema(table)),
          new WriteReservationId(UUID.randomUUID()));
    }

    @Override
    public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
      if (failWal) {
        throw new DbmsException(ErrorCode.WAL_FAILURE, "Injected append failure");
      }
      assertEquals(tx, write.tx());
      var lsn = new LogSequenceNumber(logged.size() + 1L);
      logged.put(write.reservation(), lsn);
      return lsn;
    }

    @Override
    public void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn) {
      assertEquals(logged.get(prepared.reservation()), lsn, "WAL append must precede apply");
      if (failApply) {
        throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Injected apply failure");
      }
      Row row = serializer.decode(prepared.after(), catalog.getSchema(prepared.table()));
      rows.get(prepared.table()).add(row);
      catalog.recordInsert(prepared.table(), row);
      applied.add(prepared);
      if (applied.size() == crashAfterWrites) {
        throw new AssertionError("Injected crash during sample writes");
      }
    }

    @Override
    public void cancelPrepared(PreparedWrite prepared) {
      assertFalse(logged.containsKey(prepared.reservation()));
      cancels++;
    }

    @Override
    public TransactionId begin() {
      if (failBegin) {
        throw new DbmsException(ErrorCode.WAL_FAILURE, "Injected begin failure");
      }
      begins++;
      var tx = new TransactionId(begins);
      statuses.put(tx, TransactionStatus.ACTIVE);
      return tx;
    }

    @Override
    public void commit(TransactionId tx) {
      if (failCommit) {
        throw new DbmsException(ErrorCode.WAL_FAILURE, "Injected durable commit failure");
      }
      commits++;
      statuses.put(tx, TransactionStatus.COMMITTED);
      if (crashAfterCommit) {
        throw new AssertionError("Injected crash after durable commit");
      }
    }

    @Override
    public void abort(TransactionId tx) {
      aborts++;
      if (failAbort) {
        throw new DbmsException(ErrorCode.RECOVERY_FAILURE, "Injected abort failure");
      }
      assertEquals(TransactionStatus.ACTIVE, statuses.get(tx));
      for (PreparedWrite write : applied.reversed()) {
        if (!write.tx().equals(tx)) {
          continue;
        }
        Row row = serializer.decode(write.after(), catalog.getSchema(write.table()));
        catalog.recordDelete(write.table(), row);
        rows.get(write.table()).remove(row);
      }
      statuses.put(tx, TransactionStatus.ABORTED);
    }

    @Override
    public TransactionStatus status(TransactionId tx) {
      return statuses.get(tx);
    }

    @Override
    public RowCursor openTableScan(String table, TransactionId tx) {
      var tableRows = List.copyOf(rows.getOrDefault(table, List.of()));
      var iterator = tableRows.iterator();
      return new RowCursor() {
        private int slot;

        @Override
        public Optional<StoredRow> next() {
          return iterator.hasNext()
              ? Optional.of(
                  new StoredRow(
                      new RecordId(catalog.getTableId(table), 0, slot++, 0), iterator.next()))
              : Optional.empty();
        }

        @Override
        public void close() {
          closedScans++;
        }
      };
    }

    @Override
    public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
      throw new UnsupportedOperationException();
    }
  }
}
