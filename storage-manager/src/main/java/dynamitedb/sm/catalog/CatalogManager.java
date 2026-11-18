package dynamitedb.sm.catalog;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableDefinition;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.sm.serialization.RecordSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.zip.CRC32;

/** Persistent schema, table identity, statistics and completed index definitions (SM-08, SM-09). */
public final class CatalogManager {
  private static final int MAGIC = 0x44594354;
  private static final int VERSION = 3;
  private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
  private static final int INITIALIZING_SAMPLE = 1;
  private static final int READY_SAMPLE = 2;
  private static final int PENDING_SAMPLE = 3;

  private final Map<String, Schema> schemas = new HashMap<>();
  private final Map<String, Long> tableIds = new HashMap<>();
  private final Map<String, TableStatistics> stats = new HashMap<>();
  private final Map<String, List<IndexMetadata>> indexes = new HashMap<>();
  private final Map<String, Long> rowBytes = new HashMap<>();
  private final Map<String, List<Map<Value, Long>>> frequencies = new HashMap<>();
  private final Map<String, PrimaryKey> primaryKeys = new HashMap<>();
  private final Map<String, List<ForeignKey>> foreignKeys = new HashMap<>();
  private final RecordSerializer serializer = new RecordSerializer();
  private final Path root;
  private final Path file;
  private final ToLongFunction<String> blockCounter;
  private final Predicate<Path> freshRecoveryMetadata;
  private long nextTableId = 1;
  private int sampleState;
  private boolean present;
  private boolean failed;

  public CatalogManager() {
    this(Path.of("data"));
  }

  /** Opens an existing catalog without creating files or inventing sample tables. */
  public CatalogManager(Path root) {
    this(root, null);
  }

  /**
   * The optional counter returns pages owned by the requested table, using SM1's heap mapping and
   * SM2's page information. DiskManager's global count is not a per-table count. Without a live
   * counter, SM1 supplies counts through updateBlockCount.
   */
  public CatalogManager(Path root, ToLongFunction<String> blockCounter) {
    this(root, blockCounter, entry -> false);
  }

  /**
   * Recovery may approve existing entries when it has validated their contents as metadata for a
   * fresh, empty database. Approval must cover the entire entry (including a directory's contents),
   * not just its filename. Unknown files and existing heap storage must never be approved. The
   * default constructors approve no entries; SM does not interpret FRM's private file formats.
   */
  public CatalogManager(
      Path root, ToLongFunction<String> blockCounter, Predicate<Path> freshRecoveryMetadata) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    file = this.root.resolve("catalog.db");
    this.blockCounter = blockCounter;
    this.freshRecoveryMetadata =
        Objects.requireNonNull(freshRecoveryMetadata, "freshRecoveryMetadata");
    if (!Files.notExists(file)) {
      load();
      present = true;
    }
  }

  public synchronized List<String> listTables() {
    ensureHealthy();
    return schemas.keySet().stream().sorted().toList();
  }

  public synchronized long getTableId(String table) {
    return lookup(tableIds, table);
  }

  public synchronized Schema getSchema(String table) {
    return lookup(schemas, table);
  }

  public synchronized TableStatistics getStatistics(String table) {
    var snapshot = lookup(stats, table);
    return new TableStatistics(
        snapshot.rowCount(),
        blockCount(table),
        snapshot.averageRowBytes(),
        snapshot.distinctCounts());
  }

  public synchronized List<IndexMetadata> listIndexes(String table) {
    return lookup(indexes, table);
  }

  /** Publishes a monotonically allocated ID only after the definition has been saved durably. */
  public synchronized long createTable(String table, Schema schema) {
    return createTable(new TableDefinition(table, schema, Optional.empty(), List.of()));
  }

  /** CREATE TABLE + key constraints; atomic dan durable, catalog tidak berubah bila gagal. */
  public synchronized long createTable(TableDefinition definition) {
    ensureHealthy();
    Objects.requireNonNull(definition, "definition");
    String table = definition.name();
    validateSchema(table, definition.schema());
    if (schemas.containsKey(table)) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Table already exists: " + table);
    }
    if (nextTableId == Long.MAX_VALUE) {
      throw failure("Table ID space exhausted");
    }
    requireFitsPage(table, definition.schema());
    PrimaryKey key = definition.primaryKey().orElse(null);
    Schema schema = definition.schema();
    IndexMetadata keyIndex = null;
    if (key != null) {
      requireColumns(schema, key.columns(), "PRIMARY KEY");
      schema = withNotNull(schema, key.columns());
      keyIndex =
          new IndexMetadata(
              primaryKeyIndexName(table), table, key.columns().get(0), IndexKind.HASH);
      validateName(keyIndex.name());
      if (indexNameTaken(keyIndex.name())) {
        throw new DbmsException(ErrorCode.SQL_BIND, "Index already exists: " + keyIndex.name());
      }
    }
    for (ForeignKey foreignKey : definition.foreignKeys()) {
      validateForeignKey(table, schema, key, foreignKey);
    }
    long id = nextTableId;
    Schema stored = schema;
    PrimaryKey storedKey = key;
    IndexMetadata storedIndex = keyIndex;
    mutate(
        () -> {
          addTable(table, stored);
          if (storedKey != null) {
            primaryKeys.put(table, storedKey);
            indexes.put(table, List.of(storedIndex));
          }
          foreignKeys.put(table, definition.foreignKeys());
        });
    return id;
  }

  /** DROP TABLE; RESTRICT gagal bila masih direferensikan, CASCADE hanya menghapus FK dependen. */
  public synchronized List<IndexMetadata> dropTable(String table, DropBehavior behavior) {
    ensureHealthy();
    Objects.requireNonNull(behavior, "behavior");
    List<IndexMetadata> dropped = lookup(indexes, table);
    List<ForeignKey> dependents =
        referencing(table).stream().filter(key -> !key.table().equals(table)).toList();
    if (behavior == DropBehavior.RESTRICT && !dependents.isEmpty()) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE,
          "Table "
              + table
              + " is referenced by a FOREIGN KEY on "
              + dependents.get(0).table()
              + "; use DROP TABLE ... CASCADE");
    }
    mutate(
        () -> {
          for (ForeignKey dependent : dependents) {
            foreignKeys.put(
                dependent.table(),
                foreignKeys.get(dependent.table()).stream()
                    .filter(key -> !key.equals(dependent))
                    .toList());
          }
          schemas.remove(table);
          tableIds.remove(table);
          stats.remove(table);
          indexes.remove(table);
          rowBytes.remove(table);
          frequencies.remove(table);
          primaryKeys.remove(table);
          foreignKeys.remove(table);
        });
    return dropped;
  }

  public synchronized Optional<PrimaryKey> getPrimaryKey(String table) {
    lookup(schemas, table);
    return Optional.ofNullable(primaryKeys.get(table));
  }

  public synchronized List<ForeignKey> listForeignKeys(String table) {
    return lookup(foreignKeys, table);
  }

  public synchronized List<ForeignKey> listReferencingForeignKeys(String table) {
    lookup(schemas, table);
    return referencing(table);
  }

  /** Nama index HASH yang mendukung PRIMARY KEY table (dipakai QP untuk cek uniqueness). */
  public static String primaryKeyIndexName(String table) {
    return "pk_" + table;
  }

  /** True bila ID pernah dialokasikan tetapi table-nya sudah di-drop; WAL lama boleh di-skip. */
  public synchronized boolean isDroppedTableId(long id) {
    ensureHealthy();
    return id >= 1 && id < nextTableId && !tableIds.containsValue(id);
  }

  /** Samakan definisi dengan catalog aktif untuk stage historical recovery; statistics di-reset. */
  public synchronized void replaceDefinitionsFrom(CatalogManager source) {
    ensureHealthy();
    if (Objects.requireNonNull(source, "source") == this) {
      return;
    }
    Map<String, Schema> sourceSchemas;
    Map<String, Long> sourceIds;
    Map<String, List<IndexMetadata>> sourceIndexes;
    Map<String, PrimaryKey> sourceKeys;
    Map<String, List<ForeignKey>> sourceForeignKeys;
    long sourceNextId;
    int sourceSampleState;
    synchronized (source) {
      source.ensureHealthy();
      sourceSchemas = new HashMap<>(source.schemas);
      sourceIds = new HashMap<>(source.tableIds);
      sourceIndexes = new HashMap<>(source.indexes);
      sourceKeys = new HashMap<>(source.primaryKeys);
      sourceForeignKeys = new HashMap<>(source.foreignKeys);
      sourceNextId = source.nextTableId;
      sourceSampleState = source.sampleState;
    }
    mutate(
        () -> {
          schemas.clear();
          tableIds.clear();
          stats.clear();
          indexes.clear();
          rowBytes.clear();
          frequencies.clear();
          primaryKeys.clear();
          foreignKeys.clear();
          nextTableId = Math.max(nextTableId, sourceNextId);
          sampleState = sourceSampleState;
          for (var entry : sourceSchemas.entrySet()) {
            String table = entry.getKey();
            var counts = emptyFrequencies(entry.getValue());
            schemas.put(table, entry.getValue());
            tableIds.put(table, sourceIds.get(table));
            indexes.put(table, sourceIndexes.get(table));
            foreignKeys.put(table, sourceForeignKeys.get(table));
            if (sourceKeys.containsKey(table)) {
              primaryKeys.put(table, sourceKeys.get(table));
            }
            rowBytes.put(table, 0L);
            frequencies.put(table, counts.stream().map(Map::copyOf).toList());
            stats.put(table, statistics(entry.getValue(), counts, 0, 0, 0));
          }
        });
  }

  /** SM5 calls only after the index is fully built and durable; failed builds remain private. */
  public synchronized void registerIndex(IndexMetadata metadata) {
    ensureHealthy();
    if (metadata == null || metadata.kind() == null) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Index metadata and kind are required");
    }
    validateName(metadata.name());
    var schema = getSchema(metadata.table());
    if (schema.columns().stream().noneMatch(column -> column.name().equals(metadata.column()))) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Unknown column: " + metadata.column());
    }
    if (indexes.values().stream()
        .flatMap(List::stream)
        .anyMatch(index -> index.name().equals(metadata.name()))) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Index already exists: " + metadata.name());
    }
    var definitions = new ArrayList<>(indexes.get(metadata.table()));
    definitions.add(metadata);
    mutate(() -> indexes.put(metadata.table(), List.copyOf(definitions)));
  }

  /** SM1 calls after a successful physical insert, including recovery mutations as appropriate. */
  public synchronized void recordInsert(String table, Row row) {
    Objects.requireNonNull(row, "row");
    changeRows(table, null, row);
  }

  /** SQL NULL does not contribute to a column's distinct value count. */
  public synchronized void recordDelete(String table, Row row) {
    Objects.requireNonNull(row, "row");
    changeRows(table, row, null);
  }

  public synchronized void recordUpdate(String table, Row before, Row after) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");
    changeRows(table, before, after);
  }

  /**
   * Rebuilds exact statistics from a heap scan after startup, recovery or generation activation.
   */
  public synchronized void refreshStatistics(String table, Iterable<Row> rows) {
    var schema = getSchema(table);
    Objects.requireNonNull(rows, "rows");
    var counts = emptyFrequencies(schema);
    long count = 0;
    long bytes = 0;
    for (Row row : rows) {
      bytes = Math.addExact(bytes, encodedSize(row, schema));
      count = Math.incrementExact(count);
      adjustFrequencies(counts, row, 1);
    }
    saveStatistics(table, counts, count, bytes, blockCount(table));
  }

  /** Supplies table-owned page counts when no live per-table counter was wired. */
  public synchronized void updateBlockCount(String table, long blocks) {
    var old = lookup(stats, table);
    if (blocks < 0) {
      throw failure("Negative block count for " + table);
    }
    mutate(
        () ->
            stats.put(
                table,
                new TableStatistics(
                    old.rowCount(), blocks, old.averageRowBytes(), old.distinctCounts())));
  }

  /** Reload catalog dari disk, dipakai SM1 saat generation switch. */
  public synchronized void reload() {
    schemas.clear();
    tableIds.clear();
    stats.clear();
    indexes.clear();
    rowBytes.clear();
    frequencies.clear();
    primaryKeys.clear();
    foreignKeys.clear();
    nextTableId = 1;
    sampleState = 0;
    present = false;
    failed = false;
    if (!Files.notExists(file)) {
      try {
        load();
      } catch (RuntimeException e) {
        failed = true;
        throw e;
      }
      present = true;
    }
  }

  private void changeRows(String table, Row before, Row after) {
    var schema = getSchema(table);
    int removed = before == null ? 0 : encodedSize(before, schema);
    int added = after == null ? 0 : encodedSize(after, schema);
    var counts = new ArrayList<Map<Value, Long>>();
    frequencies.get(table).forEach(values -> counts.add(new HashMap<>(values)));
    if (before != null) {
      adjustFrequencies(counts, before, -1);
    }
    if (after != null) {
      adjustFrequencies(counts, after, 1);
    }
    long count =
        Math.addExact(
            stats.get(table).rowCount(), (after == null ? 0 : 1) - (before == null ? 0 : 1));
    long bytes = Math.addExact(Math.subtractExact(rowBytes.get(table), removed), added);
    if (count < 0 || bytes < 0) {
      throw failure("Statistics underflow for " + table);
    }
    saveStatistics(table, counts, count, bytes, blockCount(table));
  }

  private void saveStatistics(
      String table, List<Map<Value, Long>> counts, long count, long bytes, long blocks) {
    var snapshot = statistics(schemas.get(table), counts, count, bytes, blocks);
    var immutableCounts = counts.stream().map(Map::copyOf).toList();
    mutate(
        () -> {
          frequencies.put(table, immutableCounts);
          rowBytes.put(table, bytes);
          stats.put(table, snapshot);
        });
  }

  private static void adjustFrequencies(List<Map<Value, Long>> counts, Row row, long delta) {
    for (int i = 0; i < counts.size(); i++) {
      var values = counts.get(i);
      var value = row.values().get(i);
      long next = Math.addExact(values.getOrDefault(value, 0L), delta);
      if (next < 0) {
        throw failure("Cannot remove a value absent from table statistics");
      }
      if (next == 0) {
        values.remove(value);
      } else {
        values.put(value, next);
      }
    }
  }

  private static TableStatistics statistics(
      Schema schema, List<Map<Value, Long>> counts, long rows, long bytes, long blocks) {
    var distinct = new HashMap<String, Long>();
    for (int i = 0; i < schema.columns().size(); i++) {
      var values = counts.get(i);
      distinct.put(
          schema.columns().get(i).name(),
          (long) values.size() - (values.containsKey(NullValue.INSTANCE) ? 1 : 0));
    }
    return new TableStatistics(
        rows, blocks, rows == 0 ? 0 : Math.toIntExact(bytes / rows), distinct);
  }

  private int encodedSize(Row row, Schema schema) {
    return serializer.encode(row, schema).encoded().copy().length;
  }

  private long blockCount(String table) {
    long count =
        blockCounter == null ? stats.get(table).blockCount() : blockCounter.applyAsLong(table);
    if (count < 0) {
      throw failure("Negative block count for " + table);
    }
    return count;
  }

  private void addTable(String table, Schema schema) {
    schemas.put(table, schema);
    tableIds.put(table, nextTableId++);
    indexes.put(table, List.of());
    foreignKeys.put(table, List.of());
    rowBytes.put(table, 0L);
    var counts = emptyFrequencies(schema);
    frequencies.put(table, counts.stream().map(Map::copyOf).toList());
    stats.put(table, statistics(schema, counts, 0, 0, 0));
  }

  private static List<Map<Value, Long>> emptyFrequencies(Schema schema) {
    var result = new ArrayList<Map<Value, Long>>();
    for (int i = 0; i < schema.columns().size(); i++) {
      result.add(new HashMap<>());
    }
    return result;
  }

  synchronized boolean hasDatabase() {
    ensureHealthy();
    return present && (sampleState == 0 || sampleState == READY_SAMPLE);
  }

  synchronized boolean requiresSampleRecovery() {
    ensureHealthy();
    return sampleState == INITIALIZING_SAMPLE;
  }

  synchronized void validateSampleDefinitions(Map<String, Schema> definitions) {
    ensureHealthy();
    if (!schemas.equals(definitions)
        || indexes.values().stream().anyMatch(list -> !list.isEmpty())) {
      throw failure("Unfinished sample catalog has unexpected definitions; refusing to reseed");
    }
  }

  synchronized void beginSampleInitialization(Map<String, Schema> definitions) {
    ensureHealthy();
    if (present) {
      if (sampleState != PENDING_SAMPLE) {
        throw new DbmsException(
            ErrorCode.INVALID_STATE, "Database already exists or requires recovery");
      }
      validateSampleDefinitions(definitions);
      return;
    }
    try {
      if (Files.exists(root)) {
        try (var entries = Files.list(root)) {
          if (entries.anyMatch(
              entry ->
                  Files.isSymbolicLink(entry)
                      || entry.getFileName().toString().equals("pages.db")
                      || entry.getFileName().toString().equals("pages.pending")
                      || !freshRecoveryMetadata.test(entry))) {
            throw failure(
                "Storage contains data or entries not validated as fresh recovery metadata; refusing to seed");
          }
        }
      }
    } catch (IOException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Cannot inspect storage directory", e);
    }
    definitions.forEach(CatalogManager::validateSchema);
    mutate(
        () -> {
          sampleState = PENDING_SAMPLE;
          definitions.keySet().stream()
              .sorted()
              .forEach(table -> addTable(table, definitions.get(table)));
        });
  }

  /** Persist before the first possible insert, so a crash never leaves writes marked retryable. */
  synchronized void startSampleWrites() {
    ensureHealthy();
    if (sampleState != PENDING_SAMPLE
        || stats.values().stream().anyMatch(stat -> stat.rowCount() != 0)) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Sample is not ready for a fresh write attempt");
    }
    mutate(() -> sampleState = INITIALIZING_SAMPLE);
  }

  /**
   * Use only after successful transaction abort or a post-recovery scan proving every table empty.
   */
  synchronized void resetSampleInitialization() {
    ensureHealthy();
    if (sampleState != INITIALIZING_SAMPLE) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "No sample write attempt to reset");
    }
    mutate(
        () -> {
          for (String table : schemas.keySet()) {
            var counts = emptyFrequencies(schemas.get(table));
            long blocks = blockCount(table);
            frequencies.put(table, counts.stream().map(Map::copyOf).toList());
            rowBytes.put(table, 0L);
            stats.put(table, statistics(schemas.get(table), counts, 0, 0, blocks));
          }
          sampleState = PENDING_SAMPLE;
        });
  }

  synchronized void completeSampleInitialization() {
    ensureHealthy();
    if (sampleState != INITIALIZING_SAMPLE) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "No sample initialization in progress");
    }
    if (stats.values().stream().anyMatch(stat -> stat.rowCount() < 50)) {
      throw failure("Sample inserts did not update statistics; wire SM1's catalog hooks first");
    }
    mutate(() -> sampleState = READY_SAMPLE);
  }

  private <T> T lookup(Map<String, T> map, String table) {
    ensureHealthy();
    T value = map.get(table);
    if (value == null) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Unknown table: " + table);
    }
    return value;
  }

  private void ensureHealthy() {
    if (failed || (present && !Files.isRegularFile(file))) {
      throw failure(
          "Catalog is unavailable; reopen storage after resolving its persistence failure");
    }
  }

  private static void validateName(String name) {
    if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Invalid catalog identifier: " + name);
    }
  }

  private static void validateSchema(String table, Schema schema) {
    validateName(table);
    if (schema == null || schema.columns().isEmpty() || schema.columns().size() > 1024) {
      throw new DbmsException(ErrorCode.SQL_BIND, "A table requires between 1 and 1024 columns");
    }
    var names = new HashSet<String>();
    for (Column column : schema.columns()) {
      validateName(column.name());
      if (!table.equals(column.relation())
          || column.dataType() == null
          || !names.add(column.name())) {
        throw new DbmsException(ErrorCode.SQL_BIND, "Invalid or duplicate column in " + table);
      }
      boolean text = column.dataType() == DataType.CHAR || column.dataType() == DataType.VARCHAR;
      if (text ? column.maxLength() <= 0 || column.maxLength() > 65535 : column.maxLength() != 0) {
        throw new DbmsException(ErrorCode.SQL_BIND, "Invalid column length: " + column.name());
      }
    }
  }

  private void validateForeignKey(String table, Schema schema, PrimaryKey key, ForeignKey foreign) {
    if (!table.equals(foreign.table())) {
      throw new DbmsException(ErrorCode.SQL_BIND, "FOREIGN KEY belongs to another table");
    }
    requireColumns(schema, foreign.columns(), "FOREIGN KEY");
    boolean self = foreign.referencedTable().equals(table);
    Schema target = self ? schema : schemas.get(foreign.referencedTable());
    PrimaryKey targetKey = self ? key : primaryKeys.get(foreign.referencedTable());
    if (target == null) {
      throw new DbmsException(
          ErrorCode.SQL_BIND, "Referenced table does not exist: " + foreign.referencedTable());
    }
    if (targetKey == null || !targetKey.columns().equals(foreign.referencedColumns())) {
      throw new DbmsException(
          ErrorCode.SQL_BIND,
          "FOREIGN KEY must reference the PRIMARY KEY of " + foreign.referencedTable());
    }
    for (int i = 0; i < foreign.columns().size(); i++) {
      DataType own = column(schema, foreign.columns().get(i)).dataType();
      DataType referenced = column(target, foreign.referencedColumns().get(i)).dataType();
      if (own != referenced) {
        throw new DbmsException(
            ErrorCode.SQL_BIND, "FOREIGN KEY column type mismatch: " + foreign.columns().get(i));
      }
    }
  }

  private static void requireColumns(Schema schema, List<String> names, String constraint) {
    var seen = new HashSet<String>();
    for (String name : names) {
      column(schema, name);
      if (!seen.add(name)) {
        throw new DbmsException(ErrorCode.SQL_BIND, constraint + " repeats column " + name);
      }
    }
  }

  private static Column column(Schema schema, String name) {
    return schema.columns().stream()
        .filter(column -> column.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new DbmsException(ErrorCode.SQL_BIND, "Unknown column: " + name));
  }

  private static Schema withNotNull(Schema schema, List<String> names) {
    return new Schema(
        schema.columns().stream()
            .map(
                c ->
                    names.contains(c.name())
                        ? new Column(c.relation(), c.name(), c.dataType(), c.maxLength(), false)
                        : c)
            .toList());
  }

  private void requireFitsPage(String table, Schema schema) {
    var values = new ArrayList<Value>();
    for (Column column : schema.columns()) {
      values.add(
          switch (column.dataType()) {
            case INTEGER -> new IntValue(0);
            case FLOAT -> new FloatValue(0);
            case CHAR, VARCHAR -> new TextValue("x".repeat(column.maxLength()));
          });
    }
    if (encodedSize(new Row(values), schema) > RecordSerializer.MAX_RECORD_SIZE) {
      throw new DbmsException(
          ErrorCode.SQL_BIND, "Widest row of " + table + " does not fit in one page");
    }
  }

  private boolean indexNameTaken(String name) {
    return indexes.values().stream().flatMap(List::stream).anyMatch(i -> i.name().equals(name));
  }

  private List<ForeignKey> referencing(String table) {
    return foreignKeys.keySet().stream()
        .sorted()
        .flatMap(owner -> foreignKeys.get(owner).stream())
        .filter(key -> key.referencedTable().equals(table))
        .toList();
  }

  /** Mutations replace immutable values, so shallow map snapshots suffice for rollback. */
  private void mutate(Runnable mutation) {
    var oldSchemas = new HashMap<>(schemas);
    var oldIds = new HashMap<>(tableIds);
    var oldStats = new HashMap<>(stats);
    var oldIndexes = new HashMap<>(indexes);
    var oldBytes = new HashMap<>(rowBytes);
    var oldFrequencies = new HashMap<>(frequencies);
    var oldKeys = new HashMap<>(primaryKeys);
    var oldForeignKeys = new HashMap<>(foreignKeys);
    long oldNextId = nextTableId;
    int oldSampleState = sampleState;
    try {
      mutation.run();
      persist();
      present = true;
    } catch (RuntimeException e) {
      restore(schemas, oldSchemas);
      restore(tableIds, oldIds);
      restore(stats, oldStats);
      restore(indexes, oldIndexes);
      restore(rowBytes, oldBytes);
      restore(frequencies, oldFrequencies);
      restore(primaryKeys, oldKeys);
      restore(foreignKeys, oldForeignKeys);
      nextTableId = oldNextId;
      sampleState = oldSampleState;
      throw e;
    }
  }

  private static <K, V> void restore(Map<K, V> target, Map<K, V> snapshot) {
    target.clear();
    target.putAll(snapshot);
  }

  private void persist() {
    Path temporary = null;
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        // Tanpa key constraints tetap ditulis sebagai versi 2, supaya build lama masih bisa
        // membaca.
        int version = constraintsInUse() ? VERSION : 2;
        out.writeInt(MAGIC);
        out.writeInt(version);
        out.writeLong(nextTableId);
        out.writeInt(sampleState);
        out.writeInt(schemas.size());
        for (String table : schemas.keySet().stream().sorted().toList()) {
          writeTable(out, table, version);
        }
      }
      byte[] payload = bytes.toByteArray();
      if (payload.length > MAX_FILE_BYTES - Long.BYTES) {
        throw failure("Catalog exceeds maximum supported size");
      }
      var checksum = new CRC32();
      checksum.update(payload);
      var buffer =
          ByteBuffer.allocate(payload.length + Long.BYTES)
              .put(payload)
              .putLong(checksum.getValue());
      buffer.flip();
      Files.createDirectories(root);
      if (!present && Files.exists(file)) {
        throw failure("Another catalog appeared during initialization; reopen storage");
      }
      temporary = Files.createTempFile(root, "catalog-", ".pending");
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (var directory = FileChannel.open(root, StandardOpenOption.READ)) {
        directory.force(true);
      } catch (IOException unsupported) {
        // Windows tidak support directory fsync; atomic rename sudah cukup.
      }
    } catch (IOException e) {
      failed = true;
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Cannot persist catalog: " + file, e);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // Orphaned temporary files are never treated as published catalogs.
        }
      }
    }
  }

  private boolean constraintsInUse() {
    return !primaryKeys.isEmpty()
        || foreignKeys.values().stream().anyMatch(keys -> !keys.isEmpty());
  }

  private void writeTable(DataOutputStream out, String table, int version) throws IOException {
    out.writeUTF(table);
    out.writeLong(tableIds.get(table));
    var columns = schemas.get(table).columns();
    out.writeInt(columns.size());
    for (Column column : columns) {
      out.writeUTF(column.name());
      out.writeUTF(column.dataType().name());
      out.writeInt(column.maxLength());
      out.writeBoolean(column.nullable());
    }
    out.writeLong(stats.get(table).rowCount());
    out.writeLong(stats.get(table).blockCount());
    out.writeLong(rowBytes.get(table));
    for (int i = 0; i < columns.size(); i++) {
      var values = frequencies.get(table).get(i);
      out.writeInt(values.size());
      var singleColumn = new Schema(List.of(columns.get(i)));
      for (var entry : values.entrySet()) {
        byte[] encoded =
            serializer.encode(new Row(List.of(entry.getKey())), singleColumn).encoded().copy();
        out.writeInt(encoded.length);
        out.write(encoded);
        out.writeLong(entry.getValue());
      }
    }
    out.writeInt(indexes.get(table).size());
    for (IndexMetadata index : indexes.get(table)) {
      out.writeUTF(index.name());
      out.writeUTF(index.column());
      out.writeUTF(index.kind().name());
    }
    if (version < 3) {
      return;
    }
    PrimaryKey key = primaryKeys.get(table);
    out.writeBoolean(key != null);
    if (key != null) {
      writeNames(out, key.columns());
    }
    List<ForeignKey> keys = foreignKeys.get(table);
    out.writeInt(keys.size());
    for (ForeignKey foreign : keys) {
      writeNames(out, foreign.columns());
      out.writeUTF(foreign.referencedTable());
      writeNames(out, foreign.referencedColumns());
    }
  }

  private static void writeNames(DataOutputStream out, List<String> names) throws IOException {
    out.writeInt(names.size());
    for (String name : names) {
      out.writeUTF(name);
    }
  }

  private static List<String> readNames(DataInputStream in) throws IOException {
    int count = readCount(in);
    var names = new ArrayList<String>(count);
    for (int i = 0; i < count; i++) {
      names.add(in.readUTF());
    }
    return List.copyOf(names);
  }

  private void load() {
    try {
      long size = Files.size(file);
      if (size < 32 || size > MAX_FILE_BYTES) {
        throw failure("Invalid catalog file size");
      }
      byte[] bytes = Files.readAllBytes(file);
      int payloadSize = bytes.length - Long.BYTES;
      var checksum = new CRC32();
      checksum.update(bytes, 0, payloadSize);
      if (ByteBuffer.wrap(bytes).getLong(payloadSize) != checksum.getValue()) {
        throw failure("Catalog checksum mismatch");
      }
      try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadSize))) {
        if (in.readInt() != MAGIC) {
          throw failure("Unsupported catalog format");
        }
        int version = in.readInt();
        if (version < 1 || version > VERSION) {
          throw failure("Unsupported catalog format");
        }
        nextTableId = in.readLong();
        sampleState = in.readInt();
        if (nextTableId < 1
            || sampleState < 0
            || sampleState > (version == 1 ? READY_SAMPLE : PENDING_SAMPLE)) {
          throw failure("Invalid catalog state");
        }
        int tableCount = readCount(in);
        var ids = new HashSet<Long>();
        var indexNames = new HashSet<String>();
        for (int i = 0; i < tableCount; i++) {
          readTable(in, ids, indexNames, version);
        }
        validateLoadedKeys();
        if (in.available() != 0) {
          throw failure("Unexpected bytes after catalog definitions");
        }
      }
    } catch (IOException | RuntimeException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Cannot load catalog: " + file, e);
    }
  }

  private void readTable(
      DataInputStream in, HashSet<Long> ids, HashSet<String> indexNames, int version)
      throws IOException {
    String table = in.readUTF();
    long id = in.readLong();
    if (id < 1 || id >= nextTableId || !ids.add(id) || schemas.containsKey(table)) {
      throw failure("Duplicate or invalid catalog table ID/name");
    }
    int columnCount = readCount(in);
    if (columnCount < 1 || columnCount > 1024) {
      throw failure("Invalid column count");
    }
    var columns = new ArrayList<Column>();
    for (int i = 0; i < columnCount; i++) {
      columns.add(
          new Column(
              table, in.readUTF(), DataType.valueOf(in.readUTF()), in.readInt(), in.readBoolean()));
    }
    var schema = new Schema(columns);
    validateSchema(table, schema);
    long rows = in.readLong();
    long blocks = in.readLong();
    long bytes = in.readLong();
    if (rows < 0
        || blocks < 0
        || bytes < 0
        || (rows == 0 ? bytes != 0 : bytes / rows > Integer.MAX_VALUE)) {
      throw failure("Invalid persisted statistics");
    }
    var counts = emptyFrequencies(schema);
    for (int i = 0; i < columnCount; i++) {
      int valueCount = readCount(in);
      long total = 0;
      var singleColumn = new Schema(List.of(columns.get(i)));
      for (int j = 0; j < valueCount; j++) {
        int length = readCount(in);
        if (length > in.available() - Long.BYTES) {
          throw failure("Truncated statistics value");
        }
        byte[] encoded = in.readNBytes(length);
        Value value =
            serializer
                .decode(new RecordImage(true, ImmutableBytes.copyOf(encoded)), singleColumn)
                .values()
                .getFirst();
        long frequency = in.readLong();
        if (frequency < 1 || counts.get(i).put(value, frequency) != null) {
          throw failure("Invalid or duplicate statistics frequency");
        }
        total = Math.addExact(total, frequency);
      }
      if (total != rows) {
        throw failure("Statistics frequencies do not match row count");
      }
    }
    var definitions = new ArrayList<IndexMetadata>();
    int indexCount = readCount(in);
    for (int i = 0; i < indexCount; i++) {
      String name = in.readUTF();
      String column = in.readUTF();
      IndexKind kind = IndexKind.valueOf(in.readUTF());
      validateName(name);
      if (!indexNames.add(name) || columns.stream().noneMatch(c -> c.name().equals(column))) {
        throw failure("Invalid persisted index definition");
      }
      definitions.add(new IndexMetadata(name, table, column, kind));
    }
    PrimaryKey key = null;
    var keys = new ArrayList<ForeignKey>();
    if (version >= 3) {
      if (in.readBoolean()) {
        key = new PrimaryKey(readNames(in));
        requireColumns(schema, key.columns(), "PRIMARY KEY");
      }
      int foreignCount = readCount(in);
      for (int i = 0; i < foreignCount; i++) {
        keys.add(new ForeignKey(table, readNames(in), in.readUTF(), readNames(in)));
      }
    }
    if (key != null) {
      primaryKeys.put(table, key);
    }
    foreignKeys.put(table, List.copyOf(keys));
    schemas.put(table, schema);
    tableIds.put(table, id);
    indexes.put(table, List.copyOf(definitions));
    rowBytes.put(table, bytes);
    frequencies.put(table, counts.stream().map(Map::copyOf).toList());
    stats.put(table, statistics(schema, counts, rows, bytes, blocks));
  }

  private void validateLoadedKeys() {
    for (String table : foreignKeys.keySet()) {
      for (ForeignKey foreign : foreignKeys.get(table)) {
        requireColumns(schemas.get(table), foreign.columns(), "FOREIGN KEY");
        PrimaryKey target = primaryKeys.get(foreign.referencedTable());
        if (target == null || !target.columns().equals(foreign.referencedColumns())) {
          throw failure("Persisted FOREIGN KEY references a missing PRIMARY KEY");
        }
      }
    }
  }

  private static int readCount(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > in.available()) {
      throw failure("Invalid catalog length/count");
    }
    return count;
  }

  private static DbmsException failure(String message) {
    return new DbmsException(ErrorCode.STORAGE_FAILURE, message);
  }
}
