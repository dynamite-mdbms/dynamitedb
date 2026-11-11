package dynamitedb.sm.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.sm.serialization.RecordSerializer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogManagerTest {
  @TempDir Path directory;

  @Test
  void absentCatalogDoesNotCreateFilesOrMockTables() {
    var root = directory.resolve("database");
    var catalog = new CatalogManager(root);
    assertEquals(List.of(), catalog.listTables());
    assertFalse(Files.exists(root));
  }

  @Test
  void identitiesAndPhysicalSchemasSurviveRestartAndKeepIncreasing() {
    var catalog = new CatalogManager(directory);
    long z = catalog.createTable("z", schema("z"));
    long a = catalog.createTable("a", schema("a"));
    assertTrue(a > z);
    var reopened = new CatalogManager(directory);
    assertEquals(List.of("a", "z"), reopened.listTables());
    assertEquals(z, reopened.getTableId("z"));
    assertEquals(a, reopened.getTableId("a"));
    assertEquals(schema("z"), reopened.getSchema("z"));
    assertTrue(reopened.createTable("b", schema("b")) > a);
  }

  @Test
  void everyMetadataLookupReportsMissingTableAsBindingError() {
    var catalog = new CatalogManager(directory);
    for (Consumer<String> lookup :
        List.<Consumer<String>>of(
            catalog::getTableId,
            catalog::getSchema,
            catalog::getStatistics,
            catalog::listIndexes)) {
      assertEquals(
          ErrorCode.SQL_BIND,
          assertThrows(DbmsException.class, () -> lookup.accept("missing")).code);
    }
  }

  @Test
  void rejectsInvalidDefinitionsWithoutPublishingThem() {
    var catalog = new CatalogManager(directory);
    var column = new Column("t", "id", DataType.INTEGER, 0, false);
    assertThrows(
        DbmsException.class, () -> catalog.createTable("t", new Schema(List.of(column, column))));
    assertThrows(
        DbmsException.class,
        () ->
            catalog.createTable(
                "t", new Schema(List.of(new Column("t", "name", DataType.CHAR, 0, false)))));
    assertThrows(DbmsException.class, () -> catalog.createTable("t", schema("other")));
    assertFalse(Files.exists(directory.resolve("catalog.db")));
    assertEquals(1, catalog.createTable("t", schema("t")));
    assertThrows(DbmsException.class, () -> catalog.createTable("t", schema("t")));
  }

  @Test
  void completedIndexesAreDurableValidatedAndReturnedAsImmutableSnapshots() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable("t", schema("t"));
    var before = catalog.listIndexes("t");
    var index = new IndexMetadata("t_id", "t", "id", IndexKind.HASH);
    catalog.registerIndex(index);
    assertTrue(before.isEmpty());
    assertEquals(List.of(index), new CatalogManager(directory).listIndexes("t"));
    byte[] published = Files.readAllBytes(directory.resolve("catalog.db"));
    assertThrows(DbmsException.class, () -> catalog.registerIndex(index));
    assertThrows(
        DbmsException.class,
        () ->
            catalog.registerIndex(new IndexMetadata("bad", "t", "missing", IndexKind.B_PLUS_TREE)));
    assertArrayEquals(published, Files.readAllBytes(directory.resolve("catalog.db")));
    assertThrows(UnsupportedOperationException.class, () -> catalog.listIndexes("t").clear());
  }

  @Test
  void insertUpdateDeleteAndRestartPreserveExactStatistics() {
    var catalog = new CatalogManager(directory);
    catalog.createTable("t", schema("t"));
    Row first = row(1, "école", 3.5);
    Row second = row(2, "école", null);
    Row changed = row(2, "new", 4.0);
    catalog.recordInsert("t", first);
    catalog.recordInsert("t", second);
    catalog.updateBlockCount("t", 2);
    var initial = catalog.getStatistics("t");
    assertEquals(2, initial.rowCount());
    assertEquals(1, initial.distinctCounts().get("name"));
    assertEquals(1, initial.distinctCounts().get("gpa"));
    assertEquals(2, initial.blockCount());
    var serializer = new RecordSerializer();
    int bytes =
        serializer.encode(first, schema("t")).encoded().copy().length
            + serializer.encode(second, schema("t")).encoded().copy().length;
    assertEquals(bytes / 2, initial.averageRowBytes());
    catalog = new CatalogManager(directory);
    catalog.recordUpdate("t", second, changed);
    assertEquals(2, catalog.getStatistics("t").distinctCounts().get("name"));
    catalog.recordDelete("t", first);
    var reopened = new CatalogManager(directory);
    assertEquals(catalog.getStatistics("t"), reopened.getStatistics("t"));
    assertEquals(1, reopened.getStatistics("t").rowCount());
    assertEquals(1, reopened.getStatistics("t").distinctCounts().get("gpa"));
    reopened.recordDelete("t", changed);
    assertEquals(0, reopened.getStatistics("t").rowCount());
    assertEquals(0, reopened.getStatistics("t").averageRowBytes());
    assertTrue(
        reopened.getStatistics("t").distinctCounts().values().stream().allMatch(n -> n == 0));
  }

  @Test
  void refreshReplacesStatisticsAndUsesLiveTablePageCounts() {
    var pages = new AtomicLong(3);
    var catalog = new CatalogManager(directory, table -> pages.get());
    catalog.createTable("t", schema("t"));
    catalog.recordInsert("t", row(99, "discard", 1.0));
    catalog.refreshStatistics("t", List.of(row(1, "same", null), row(2, "same", null)));
    assertEquals(2, catalog.getStatistics("t").rowCount());
    assertEquals(1, catalog.getStatistics("t").distinctCounts().get("name"));
    assertEquals(0, catalog.getStatistics("t").distinctCounts().get("gpa"));
    assertEquals(3, new CatalogManager(directory).getStatistics("t").blockCount());
    pages.set(8);
    assertEquals(8, new StorageCatalogAdapter(catalog).getStatistics("t").blockCount());
    pages.set(-1);
    assertThrows(DbmsException.class, () -> catalog.getStatistics("t"));
  }

  @Test
  void invalidRowsAndDeletesDoNotChangePublishedStatistics() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable("t", schema("t"));
    Row existing = row(1, "valid", 2.0);
    catalog.recordInsert("t", existing);
    byte[] before = Files.readAllBytes(directory.resolve("catalog.db"));
    assertThrows(
        DbmsException.class, () -> catalog.recordInsert("t", new Row(List.of(new IntValue(1)))));
    assertThrows(DbmsException.class, () -> catalog.recordDelete("t", row(9, "absent", 3.0)));
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.db")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> catalog.getStatistics("t").distinctCounts().clear());
  }

  @Test
  void truncatedCorruptAndUnsupportedCatalogsAreRejectedWithoutBeingOverwritten() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable("t", schema("t"));
    var file = directory.resolve("catalog.db");
    byte[] valid = Files.readAllBytes(file);
    var corruptions = new ArrayList<byte[]>();
    corruptions.add(new byte[] {1, 2, 3});
    byte[] damaged = valid.clone();
    damaged[24] ^= 1;
    corruptions.add(damaged);
    byte[] future = valid.clone();
    ByteBuffer.wrap(future).putInt(4, 900);
    var checksum = new CRC32();
    checksum.update(future, 0, future.length - Long.BYTES);
    ByteBuffer.wrap(future).putLong(future.length - Long.BYTES, checksum.getValue());
    corruptions.add(future);
    for (byte[] bytes : corruptions) {
      Files.write(file, bytes);
      assertEquals(
          ErrorCode.STORAGE_FAILURE,
          assertThrows(DbmsException.class, () -> new CatalogManager(directory)).code);
      assertArrayEquals(bytes, Files.readAllBytes(file));
    }
  }

  @Test
  void lostCatalogAndIoFailuresDoNotCreateReplacementDatabases() throws Exception {
    var root = directory.resolve("not-a-directory");
    var catalog = new CatalogManager(root);
    Files.writeString(root, "existing bytes");
    assertEquals(
        ErrorCode.STORAGE_FAILURE,
        assertThrows(DbmsException.class, () -> catalog.createTable("t", schema("t"))).code);
    assertEquals("existing bytes", Files.readString(root));
    assertThrows(DbmsException.class, catalog::listTables);
    var healthy = new CatalogManager(directory.resolve("healthy"));
    healthy.createTable("t", schema("t"));
    Files.delete(directory.resolve("healthy/catalog.db"));
    assertThrows(DbmsException.class, () -> healthy.recordInsert("t", row(1, "lost", null)));
    assertFalse(Files.exists(directory.resolve("healthy/catalog.db")));
  }

  @Test
  void concurrentStatisticsUpdatesDoNotLoseRows() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable("t", schema("t"));
    try (var workers = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 20; i++) {
        int id = i;
        tasks.add(
            () -> {
              catalog.recordInsert("t", row(id, "shared", 3.0));
              return null;
            });
      }
      for (var result : workers.invokeAll(tasks)) {
        result.get();
      }
    }
    var stats = new CatalogManager(directory).getStatistics("t");
    assertEquals(20, stats.rowCount());
    assertEquals(20, stats.distinctCounts().get("id"));
    assertEquals(1, stats.distinctCounts().get("name"));
  }

  @Test
  void adapterDelegatesAllCatalogOperations() {
    var catalog = new CatalogManager(directory);
    catalog.createTable("t", schema("t"));
    catalog.registerIndex(new IndexMetadata("idx", "t", "id", IndexKind.B_PLUS_TREE));
    var adapter = new StorageCatalogAdapter(catalog);
    assertEquals(catalog.listTables(), adapter.listTables());
    assertEquals(catalog.getTableId("t"), adapter.getTableId("t"));
    assertEquals(catalog.getSchema("t"), adapter.getSchema("t"));
    assertEquals(catalog.getStatistics("t"), adapter.getStatistics("t"));
    assertEquals(catalog.listIndexes("t"), adapter.listIndexes("t"));
  }

  private static Schema schema(String table) {
    return new Schema(
        List.of(
            new Column(table, "id", DataType.INTEGER, 0, false),
            new Column(table, "name", DataType.VARCHAR, 50, false),
            new Column(table, "gpa", DataType.FLOAT, 0, true),
            new Column(table, "grade", DataType.CHAR, 2, true)));
  }

  private static Row row(int id, String name, Double gpa) {
    return new Row(
        List.of(
            new IntValue(id),
            new TextValue(name),
            gpa == null ? NullValue.INSTANCE : new FloatValue(gpa),
            new TextValue("A")));
  }
}
