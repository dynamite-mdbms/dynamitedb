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
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableDefinition;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogDdlTest {
  @TempDir Path directory;

  @Test
  void createReopenDropReopenKeepsKeysAndIdentity() {
    var catalog = new CatalogManager(directory);
    long deptId = catalog.createTable(dept());
    long empId = catalog.createTable(emp());

    var reopened = new CatalogManager(directory);
    assertEquals(deptId, reopened.getTableId("dept"));
    assertEquals(Optional.of(new PrimaryKey(List.of("id"))), reopened.getPrimaryKey("dept"));
    assertEquals(List.of(empToDept()), reopened.listForeignKeys("emp"));
    assertEquals(List.of(empToDept()), reopened.listReferencingForeignKeys("dept"));
    assertEquals(
        List.of(new IndexMetadata("pk_dept", "dept", "id", IndexKind.HASH)),
        reopened.listIndexes("dept"));
    assertFalse(reopened.getSchema("dept").columns().get(0).nullable(), "PK column is NOT NULL");

    reopened.dropTable("emp", DropBehavior.RESTRICT);
    var afterDrop = new CatalogManager(directory);
    assertEquals(List.of("dept"), afterDrop.listTables());
    assertEquals(List.of(), afterDrop.listReferencingForeignKeys("dept"));
    assertTrue(afterDrop.isDroppedTableId(empId));
    assertFalse(afterDrop.isDroppedTableId(deptId));
    assertFalse(afterDrop.isDroppedTableId(empId + 1), "never allocated is not 'dropped'");
    assertBind(() -> afterDrop.getSchema("emp"));
  }

  @Test
  void restrictFailsWhileReferencedAndCascadeOnlyRemovesDependentKeys() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable(dept());
    catalog.createTable(emp());
    byte[] before = Files.readAllBytes(directory.resolve("catalog.db"));

    var error =
        assertThrows(DbmsException.class, () -> catalog.dropTable("dept", DropBehavior.RESTRICT));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.db")));

    catalog.dropTable("dept", DropBehavior.CASCADE);
    var reopened = new CatalogManager(directory);
    assertEquals(List.of("emp"), reopened.listTables(), "CASCADE keeps dependent tables");
    assertEquals(List.of(), reopened.listForeignKeys("emp"));
    assertTrue(reopened.getPrimaryKey("emp").isPresent());
  }

  @Test
  void selfReferenceDoesNotBlockRestrict() {
    var catalog = new CatalogManager(directory);
    catalog.createTable(
        new TableDefinition(
            "node",
            schema(
                "node",
                col("node", "id", DataType.INTEGER, 0),
                col("node", "parent", DataType.INTEGER, 0)),
            Optional.of(new PrimaryKey(List.of("id"))),
            List.of(new ForeignKey("node", List.of("parent"), "node", List.of("id")))));
    catalog.dropTable("node", DropBehavior.RESTRICT);
    assertEquals(List.of(), catalog.listTables());
  }

  @Test
  void invalidDefinitionsAreRejectedAndLeaveCatalogUnchanged() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable(dept());
    byte[] before = Files.readAllBytes(directory.resolve("catalog.db"));

    assertBind(() -> catalog.createTable(dept()));
    assertBind(() -> catalog.createTable(plain("t", col("t", "c", DataType.CHAR, 0))));
    assertBind(() -> catalog.createTable(plain("t", col("t", "c", DataType.VARCHAR, 5000))));
    assertBind(() -> catalog.createTable(plain("t", new Column("t", "c", null, 0, true))));
    assertBind(() -> catalog.createTable(plain("t", col("t", "n", DataType.INTEGER, 4))));
    assertBind(() -> catalog.createTable(withFk("missing", List.of("id"))));
    assertBind(() -> catalog.createTable(withFk("dept", List.of("name"))));
    assertBind(
        () ->
            catalog.createTable(
                new TableDefinition(
                    "t",
                    schema("t", col("t", "d", DataType.VARCHAR, 10)),
                    Optional.empty(),
                    List.of(new ForeignKey("t", List.of("d"), "dept", List.of("id"))))));
    assertBind(
        () ->
            catalog.createTable(
                new TableDefinition(
                    "t",
                    schema("t", col("t", "id", DataType.INTEGER, 0)),
                    Optional.of(new PrimaryKey(List.of("nope"))),
                    List.of())));
    assertBind(
        () ->
            catalog.createTable(
                new TableDefinition(
                    "t",
                    schema("t", col("t", "id", DataType.INTEGER, 0)),
                    Optional.of(new PrimaryKey(List.of("id", "id"))),
                    List.of())));

    assertEquals(List.of("dept"), catalog.listTables());
    assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.db")));
  }

  @Test
  void catalogsWithoutConstraintsKeepTheVersionTwoLayout() throws Exception {
    var catalog = new CatalogManager(directory);
    catalog.createTable("legacy", schema("legacy", col("legacy", "id", DataType.INTEGER, 0)));
    assertEquals(2, formatVersion());
    catalog.createTable(dept());
    assertEquals(3, formatVersion());
    catalog.dropTable("dept", DropBehavior.RESTRICT);
    assertEquals(2, formatVersion());
  }

  private int formatVersion() throws Exception {
    return ByteBuffer.wrap(Files.readAllBytes(directory.resolve("catalog.db"))).getInt(4);
  }

  private static TableDefinition dept() {
    return new TableDefinition(
        "dept",
        schema(
            "dept",
            col("dept", "id", DataType.INTEGER, 0),
            col("dept", "name", DataType.VARCHAR, 30)),
        Optional.of(new PrimaryKey(List.of("id"))),
        List.of());
  }

  private static TableDefinition emp() {
    return new TableDefinition(
        "emp",
        schema(
            "emp",
            col("emp", "id", DataType.INTEGER, 0),
            col("emp", "dept_id", DataType.INTEGER, 0)),
        Optional.of(new PrimaryKey(List.of("id"))),
        List.of(empToDept()));
  }

  private static ForeignKey empToDept() {
    return new ForeignKey("emp", List.of("dept_id"), "dept", List.of("id"));
  }

  private static TableDefinition withFk(String target, List<String> targetColumns) {
    return new TableDefinition(
        "t",
        schema("t", col("t", "ref", DataType.INTEGER, 0)),
        Optional.empty(),
        List.of(new ForeignKey("t", List.of("ref"), target, targetColumns)));
  }

  private static TableDefinition plain(String table, Column column) {
    return new TableDefinition(table, schema(table, column), Optional.empty(), List.of());
  }

  private static Schema schema(String table, Column... columns) {
    return new Schema(List.of(columns));
  }

  private static Column col(String table, String name, DataType type, int length) {
    return new Column(table, name, type, length, true);
  }

  private static void assertBind(Runnable action) {
    assertEquals(ErrorCode.SQL_BIND, assertThrows(DbmsException.class, action::run).getCode());
  }
}
