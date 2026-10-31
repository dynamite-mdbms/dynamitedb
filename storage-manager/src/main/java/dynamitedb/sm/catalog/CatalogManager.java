package dynamitedb.sm.catalog;

import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Katalog skema, ID tabel, dan statistik tabel (SM-08, SM-09). */
public final class CatalogManager {
  private final Map<String, Schema> schemas = new HashMap<>();
  private final Map<String, Long> tableIds = new HashMap<>();
  private final Map<String, TableStatistics> stats = new HashMap<>();

  public CatalogManager() {
    registerMockTable(
        1,
        "student",
        new Column("student", "student_id", DataType.INTEGER, 0, false),
        new Column("student", "name", DataType.VARCHAR, 50, false),
        new Column("student", "gpa", DataType.FLOAT, 0, true),
        new Column("student", "grade", DataType.CHAR, 1, true));
    registerMockTable(
        2,
        "course",
        new Column("course", "course_id", DataType.INTEGER, 0, false),
        new Column("course", "title", DataType.VARCHAR, 50, false),
        new Column("course", "credits", DataType.INTEGER, 0, false),
        new Column("course", "code", DataType.CHAR, 6, false));
    registerMockTable(
        3,
        "enrollment",
        new Column("enrollment", "student_id", DataType.INTEGER, 0, false),
        new Column("enrollment", "course_id", DataType.INTEGER, 0, false),
        new Column("enrollment", "score", DataType.FLOAT, 0, true),
        new Column("enrollment", "status", DataType.CHAR, 1, true));
  }

  public List<String> listTables() {
    // TODO: daftar tabel dari katalog aktif
    return schemas.keySet().stream().sorted().toList();
  }

  public long getTableId(String table) {
    // TODO: ID tabel stabil yang tersimpan, bukan dari hash nama
    return lookup(tableIds, table);
  }

  public Schema getSchema(String table) {
    // TODO: skema sesuai urutan kolom fisik beserta panjang dan nullability
    return lookup(schemas, table);
  }

  public TableStatistics getStatistics(String table) {
    // TODO: kumpulkan dan perbarui statistik bersama SM1 sampai SM3
    return lookup(stats, table);
  }

  public List<IndexMetadata> listIndexes(String table) {
    // TODO: definisi index yang sudah lengkap dan durable
    return List.of();
  }

  private void registerMockTable(long id, String table, Column... columns) {
    Map<String, Long> distinct = new HashMap<>();
    for (Column column : columns) {
      distinct.put(column.name(), 3L);
    }
    schemas.put(table, new Schema(List.of(columns)));
    tableIds.put(table, id);
    stats.put(table, new TableStatistics(3, 1, 64, distinct));
  }

  private static <T> T lookup(Map<String, T> map, String table) {
    T value = map.get(table);
    if (value == null) {
      throw new IllegalArgumentException("Unknown table: " + table);
    }
    return value;
  }
}
