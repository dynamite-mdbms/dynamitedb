package dynamitedb.sm.heap;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.Page;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.index.IndexRowCursor;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Physical record ops (SM-04..07): two-phase write, recovery, scan, index. */
public final class RecordStore {
  private static final TransactionId RECOVERY_TX = new TransactionId(0);
  private static final WriteReservationId RECOVERY_RESERVATION =
      new WriteReservationId(new UUID(0L, 0L));

  private final HeapFile heap;
  private final BufferPool buffer;
  private final RecordSerializer serializer;
  private final IndexManager indexes;
  private final CatalogManager catalog;

  private final Map<WriteReservationId, PreparedWrite> pending = new HashMap<>();
  private final Map<WriteReservationId, Page> pinned = new HashMap<>();
  private final Set<RecordId> busy = new HashSet<>();
  private boolean indexesLoaded;

  public RecordStore(
      HeapFile heap,
      BufferPool buffer,
      RecordSerializer serializer,
      IndexManager indexes,
      CatalogManager catalog) {
    this.heap = Objects.requireNonNull(heap, "heap");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /** Read applied row image; reservation belum terlihat. */
  public RecordImage read(RecordId rid) {
    return heap.read(Objects.requireNonNull(rid, "rid"));
  }

  /** Reserve slot tanpa image (widest row); release dengan release(). */
  public RecordId reserve(String table) {
    return heap.allocateSlot(table);
  }

  /** Release slot dari reserve(String). */
  public void release(RecordId rid) {
    heap.releaseSlot(rid);
  }

  // ---------------------------------------------------------------- two-phase write

  public synchronized PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
    Objects.requireNonNull(tx, "tx");
    Schema schema = catalog.getSchema(Objects.requireNonNull(table, "table"));
    RecordImage after = serializer.encode(Objects.requireNonNull(row, "row"), schema);
    RecordId rid = heap.allocateSlot(table, after);
    var prepared = new PreparedWrite(tx, table, rid, RecordImage.absent(), after, newId());
    return hold(prepared, true);
  }

  public synchronized PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
    Objects.requireNonNull(tx, "tx");
    String table = requireTable(rid);
    requireIdle(rid);
    RecordImage before = requireExisting(rid);
    RecordImage after =
        serializer.encode(Objects.requireNonNull(row, "row"), catalog.getSchema(table));
    heap.reserveUpdate(rid, after);
    return hold(new PreparedWrite(tx, table, rid, before, after, newId()), true);
  }

  public synchronized PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
    Objects.requireNonNull(tx, "tx");
    String table = requireTable(rid);
    requireIdle(rid);
    RecordImage before = requireExisting(rid);
    return hold(new PreparedWrite(tx, table, rid, before, RecordImage.absent(), newId()), false);
  }

  /** Apply reservation dengan LSN dari WAL; single-use. */
  public synchronized void apply(PreparedWrite prepared, LogSequenceNumber lsn) {
    Objects.requireNonNull(prepared, "prepared");
    Objects.requireNonNull(lsn, "lsn");
    if (lsn.value() < 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "LSN tidak boleh negatif");
    }
    PreparedWrite stored = pending.get(prepared.reservation());
    if (stored == null) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Reservation tidak dikenal atau sudah dipakai");
    }
    if (!stored.equals(prepared)) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "PreparedWrite tidak cocok dengan reservation-nya");
    }
    ensureIndexesLoaded();
    Page page = take(prepared);
    RecordId rid = prepared.rid();
    try {
      ImmutableBytes data = page.getData();
      RecordImage current = serializer.readSlot(data, rid.slotNumber(), rid.generation());
      if (!current.equals(prepared.before())) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE, "Slot content berubah sejak prepare: " + rid);
      }
      ImmutableBytes updated =
          prepared.after().exists()
              ? serializer.writeSlot(data, rid.slotNumber(), rid.generation(), prepared.after())
              : serializer.deleteSlot(data, rid.slotNumber(), rid.generation());
      // pageLSN tidak boleh mundur; page di-pin sejak prepare jadi tidak ter-flush.
      long pageLsn = Math.max(lsn.value(), page.getPageLsn().value());
      buffer.updatePage(page, updated, new LogSequenceNumber(pageLsn));
      heap.releaseSlot(rid);
      heap.noteFreeSpace(page.getId(), updated);
    } finally {
      heap.releaseSlot(rid);
      buffer.unpin(page);
    }
    // Bila index/statistics gagal, caller abort via CCM dan FRM rollback heap + index.
    indexes.onWrite(prepared);
    recordStatistics(prepared.table(), prepared.before(), prepared.after());
  }

  /** Cancel reservation yang belum di-apply (idempotent). */
  public synchronized void cancel(PreparedWrite prepared) {
    Objects.requireNonNull(prepared, "prepared");
    PreparedWrite stored = pending.get(prepared.reservation());
    if (stored == null || !stored.equals(prepared)) {
      return;
    }
    heap.releaseSlot(prepared.rid());
    buffer.unpin(take(prepared));
  }

  // ---------------------------------------------------------------- recovery (contract F)

  /** Idempotent redo/undo: apply hanya bila pageLSN < lsn; index dan statistics ikut. */
  public synchronized void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
    Objects.requireNonNull(mutation, "mutation");
    Objects.requireNonNull(lsn, "lsn");
    RecordId rid = mutation.rid();
    String table =
        tableName(rid.tableId())
            .orElseThrow(
                () ->
                    new DbmsException(
                        ErrorCode.RECOVERY_FAILURE, "Recovery table tidak dikenal: " + rid));
    heap.adopt(rid.tableId(), rid.pageNumber());
    RecordImage before;
    Page page = buffer.pin(rid.pageNumber());
    try {
      if (page.getPageLsn().value() >= lsn.value()) {
        return;
      }
      ImmutableBytes data = page.getData();
      before = serializer.readSlot(data, rid.slotNumber(), rid.generation());
      ImmutableBytes updated =
          serializer.writeSlot(data, rid.slotNumber(), rid.generation(), mutation.targetImage());
      buffer.updatePage(page, updated, lsn);
      heap.noteFreeSpace(page.getId(), updated);
    } finally {
      buffer.unpin(page);
    }
    indexes.onWrite(
        new PreparedWrite(
            RECOVERY_TX, table, rid, before, mutation.targetImage(), RECOVERY_RESERVATION));
    try {
      recordStatistics(table, before, mutation.targetImage());
    } catch (DbmsException e) {
      // Statistics bisa sudah ter-update sebelum crash; di-refresh di rebuildIndexes().
    }
  }

  /** Load index definitions dari catalog, rebuild dari heap, refresh statistics. */
  public synchronized void rebuildIndexes() {
    loadIndexDefinitions();
    indexes.rebuildAll();
    indexesLoaded = true;
    for (String table : catalog.listTables()) {
      catalog.updateBlockCount(table, heap.pageCount(table));
      catalog.refreshStatistics(table, rowsOf(table));
    }
  }

  /** Reset runtime state setelah generation switch. */
  public synchronized void resetAfterGenerationSwitch() {
    for (Page page : pinned.values()) {
      try {
        buffer.unpin(page);
      } catch (DbmsException e) {
        // Frame lama sudah di-discard bersama generation sebelumnya.
      }
    }
    pending.clear();
    pinned.clear();
    busy.clear();
    heap.reload();
    indexes.clear();
    indexesLoaded = false;
  }

  // ---------------------------------------------------------------- read dan index

  public RowCursor openScan(String table) {
    Schema schema = catalog.getSchema(table);
    long tableId = catalog.getTableId(table);
    return new HeapRowCursor(heap, tableId, schema, heap.pages(table), buffer, serializer);
  }

  public Optional<StoredRow> fetch(RecordId rid) {
    Objects.requireNonNull(rid, "rid");
    Optional<String> table = tableName(rid.tableId());
    if (table.isEmpty()) {
      return Optional.empty();
    }
    RecordImage image = read(rid);
    if (!image.exists()) {
      return Optional.empty();
    }
    return Optional.of(
        new StoredRow(rid, serializer.decode(image, catalog.getSchema(table.get()))));
  }

  public RowCursor openIndexScan(String index, IndexPredicate predicate) {
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(predicate, "predicate");
    List<RecordId> rids;
    Schema schema;
    synchronized (this) {
      ensureIndexesLoaded();
      IndexMetadata meta =
          findIndex(index)
              .orElseThrow(
                  () -> new DbmsException(ErrorCode.SQL_BIND, "Index tidak dikenal: " + index));
      schema = catalog.getSchema(meta.table());
      Value key = promote(predicate.value(), column(schema, meta.column()).dataType());
      rids = indexes.lookup(index, new IndexPredicate(predicate.operator(), key));
    }
    return new IndexRowCursor(this, rids.iterator(), schema, buffer, serializer);
  }

  /** Build index dari heap lalu publish ke catalog; di-drop bila publish gagal. */
  public synchronized void createIndex(IndexMetadata meta) {
    Objects.requireNonNull(meta, "meta");
    ensureIndexesLoaded();
    Schema schema = catalog.getSchema(meta.table());
    if (schema.columns().stream().map(Column::name).noneMatch(meta.column()::equals)) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Column tidak ada: " + meta.column());
    }
    if (indexes.hasIndex(meta.name()) || findIndex(meta.name()).isPresent()) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Index sudah ada: " + meta.name());
    }
    indexes.registerSchema(meta.table(), schema);
    indexes.createIndex(meta);
    try {
      catalog.registerIndex(meta);
    } catch (RuntimeException e) {
      indexes.dropIndex(meta.name());
      throw e;
    }
  }

  /** Table name untuk catalog table ID. */
  public Optional<String> tableName(long tableId) {
    for (String table : catalog.listTables()) {
      if (catalog.getTableId(table) == tableId) {
        return Optional.of(table);
      }
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------- internal

  private PreparedWrite hold(PreparedWrite prepared, boolean reservedSlot) {
    Page page;
    try {
      page = heap.readPage(prepared.rid().pageNumber());
    } catch (RuntimeException e) {
      if (reservedSlot) {
        heap.releaseSlot(prepared.rid());
      }
      throw e;
    }
    pending.put(prepared.reservation(), prepared);
    pinned.put(prepared.reservation(), page);
    busy.add(prepared.rid());
    return prepared;
  }

  private Page take(PreparedWrite prepared) {
    pending.remove(prepared.reservation());
    busy.remove(prepared.rid());
    return pinned.remove(prepared.reservation());
  }

  private void ensureIndexesLoaded() {
    if (indexesLoaded) {
      return;
    }
    loadIndexDefinitions();
    indexesLoaded = true;
  }

  private void loadIndexDefinitions() {
    for (String table : catalog.listTables()) {
      indexes.registerSchema(table, catalog.getSchema(table));
      for (IndexMetadata meta : catalog.listIndexes(table)) {
        if (!indexes.hasIndex(meta.name())) {
          indexes.createIndex(meta);
        }
      }
    }
  }

  private Optional<IndexMetadata> findIndex(String name) {
    for (String table : catalog.listTables()) {
      for (IndexMetadata meta : catalog.listIndexes(table)) {
        if (meta.name().equals(name)) {
          return Optional.of(meta);
        }
      }
    }
    return Optional.empty();
  }

  /** Samakan numeric key dengan column type supaya HASH lookup = scan. */
  private static Value promote(Value value, DataType type) {
    if (type == DataType.FLOAT && value instanceof IntValue i) {
      return new FloatValue(i.value());
    }
    if (type == DataType.INTEGER
        && value instanceof FloatValue f
        && f.value() == Math.rint(f.value())
        && f.value() >= Integer.MIN_VALUE
        && f.value() <= Integer.MAX_VALUE) {
      return new IntValue((int) f.value());
    }
    return value;
  }

  private static Column column(Schema schema, String name) {
    return schema.columns().stream()
        .filter(c -> c.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new DbmsException(ErrorCode.SQL_BIND, "Column tidak ada: " + name));
  }

  private List<Row> rowsOf(String table) {
    var rows = new ArrayList<Row>();
    try (RowCursor cursor = openScan(table)) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.get().row());
      }
    }
    return rows;
  }

  private void recordStatistics(String table, RecordImage before, RecordImage after) {
    if (!before.exists() && !after.exists()) {
      return;
    }
    Schema schema = catalog.getSchema(table);
    if (!before.exists()) {
      catalog.recordInsert(table, serializer.decode(after, schema));
    } else if (!after.exists()) {
      catalog.recordDelete(table, serializer.decode(before, schema));
    } else {
      catalog.recordUpdate(
          table, serializer.decode(before, schema), serializer.decode(after, schema));
    }
  }

  private String requireTable(RecordId rid) {
    Objects.requireNonNull(rid, "rid");
    return tableName(rid.tableId())
        .orElseThrow(
            () ->
                new DbmsException(ErrorCode.SQL_BIND, "Table ID tidak dikenal: " + rid.tableId()));
  }

  private void requireIdle(RecordId rid) {
    if (busy.contains(rid)) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Row sedang di-reserve: " + rid);
    }
  }

  private RecordImage requireExisting(RecordId rid) {
    RecordImage image = read(rid);
    if (!image.exists()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Row tidak ada: " + rid);
    }
    return image;
  }

  private static WriteReservationId newId() {
    return new WriteReservationId(UUID.randomUUID());
  }
}
