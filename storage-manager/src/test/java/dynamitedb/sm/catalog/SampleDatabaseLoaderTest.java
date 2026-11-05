package dynamitedb.sm.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  void walFailureCancelsReservationAbortsAndPreventsReseedingAfterRestart() throws Exception {
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
    byte[] interrupted = Files.readAllBytes(directory.resolve("catalog.db"));
    ports.failWal = false;
    assertEquals(
        ErrorCode.STORAGE_FAILURE,
        assertThrows(
                DbmsException.class,
                () -> ports.loader().loadIfAbsent(new CatalogManager(directory)))
            .code);
    assertEquals(1, ports.begins);
    assertArrayEquals(interrupted, Files.readAllBytes(directory.resolve("catalog.db")));
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
    assertThrows(
        DbmsException.class, () -> ports.loader().loadIfAbsent(new CatalogManager(directory)));
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
      implements WriteStore, ChangeLogger, TransactionManager {
    private final CatalogManager catalog;
    private final RecordSerializer serializer = new RecordSerializer();
    private final Map<String, List<Row>> rows = new HashMap<>();
    private final Map<WriteReservationId, LogSequenceNumber> logged = new HashMap<>();
    private final List<PreparedWrite> applied = new ArrayList<>();
    private int begins;
    private int commits;
    private int aborts;
    private int cancels;
    private boolean failWal;
    private boolean failApply;
    private boolean failCommit;

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
    }

    @Override
    public void cancelPrepared(PreparedWrite prepared) {
      assertFalse(logged.containsKey(prepared.reservation()));
      cancels++;
    }

    @Override
    public TransactionId begin() {
      begins++;
      return new TransactionId(begins);
    }

    @Override
    public void commit(TransactionId tx) {
      if (failCommit) {
        throw new DbmsException(ErrorCode.WAL_FAILURE, "Injected durable commit failure");
      }
      commits++;
    }

    @Override
    public void abort(TransactionId tx) {
      aborts++;
      for (PreparedWrite write : applied.reversed()) {
        Row row = serializer.decode(write.after(), catalog.getSchema(write.table()));
        catalog.recordDelete(write.table(), row);
        rows.get(write.table()).remove(row);
      }
    }

    @Override
    public TransactionStatus status(TransactionId tx) {
      return aborts > 0
          ? TransactionStatus.ABORTED
          : commits > 0 ? TransactionStatus.COMMITTED : TransactionStatus.ACTIVE;
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
