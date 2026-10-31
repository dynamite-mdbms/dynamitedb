package dynamitedb.sm.facade;

import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pintu masuk SM untuk QP: baca, tulis dua tahap, dan pembuatan index. */
public final class StorageManagerImpl implements TupleReader, WriteStore, IndexAdmin {
  private static final int MOCK_ROWS = 3;

  private final RecordStore records;
  private final CatalogManager catalog;
  private final IndexManager indexes;

  public StorageManagerImpl(RecordStore records, CatalogManager catalog, IndexManager indexes) {
    this.records = Objects.requireNonNull(records, "records");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
  }

  @Override
  public RowCursor openTableScan(String table, TransactionId tx) {
    // TODO: buka HeapRowCursor dengan ID dan skema tabel dari katalog
    return cursorOf(mockRows(catalog.getTableId(table), catalog.getSchema(table)));
  }

  @Override
  public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
    // TODO: ambil RID dari IndexManager lalu buka IndexRowCursor
    return cursorOf(List.of());
  }

  @Override
  public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
    // TODO: baca image, cek inkarnasi slot, decode dengan skema tabel
    return tableName(rid.tableId())
        .map(table -> mockRows(rid.tableId(), catalog.getSchema(table)))
        .filter(rows -> rid.slotNumber() >= 0 && rid.slotNumber() < rows.size())
        .map(rows -> rows.get(rid.slotNumber()));
  }

  @Override
  public PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
    // TODO: reservasi lewat records.reserve, heap dan index belum diubah
    RecordId rid = new RecordId(catalog.getTableId(table), 0, MOCK_ROWS, 0);
    return new PreparedWrite(tx, table, rid, image(false), image(true), newReservation());
  }

  @Override
  public PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
    // TODO: before dan after image beserta reservasi tanpa mengubah baris
    return new PreparedWrite(
        tx, requireTableName(rid), rid, image(true), image(true), newReservation());
  }

  @Override
  public PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
    // TODO: reservasi RID dengan before image dan after image kosong
    return new PreparedWrite(
        tx, requireTableName(rid), rid, image(true), image(false), newReservation());
  }

  @Override
  public void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn) {
    records.apply(prepared, lsn);
  }

  @Override
  public void cancelPrepared(PreparedWrite prepared) {
    records.cancel(prepared);
  }

  @Override
  public void createIndex(String name, String table, String column, IndexKind kind) {
    indexes.createIndex(new IndexMetadata(name, table, column, kind));
  }

  private Optional<String> tableName(long tableId) {
    return catalog.listTables().stream()
        .filter(table -> catalog.getTableId(table) == tableId)
        .findFirst();
  }

  private String requireTableName(RecordId rid) {
    return tableName(rid.tableId())
        .orElseThrow(() -> new IllegalArgumentException("Unknown table ID: " + rid.tableId()));
  }

  private static List<StoredRow> mockRows(long tableId, Schema schema) {
    List<StoredRow> rows = new ArrayList<>();
    for (int slot = 0; slot < MOCK_ROWS; slot++) {
      List<Value> values = new ArrayList<>();
      for (Column column : schema.columns()) {
        values.add(mockValue(column, slot + 1));
      }
      rows.add(new StoredRow(new RecordId(tableId, 0, slot, 0), new Row(values)));
    }
    return rows;
  }

  private static Value mockValue(Column column, int n) {
    return switch (column.dataType()) {
      case INTEGER -> new IntValue(n);
      case FLOAT -> new FloatValue(n * 1.5);
      case CHAR, VARCHAR -> {
        String text = column.name() + n;
        yield new TextValue(text.substring(0, Math.min(text.length(), column.maxLength())));
      }
    };
  }

  private static RecordImage image(boolean exists) {
    return new RecordImage(exists, ImmutableBytes.copyOf(new byte[0]));
  }

  private static WriteReservationId newReservation() {
    return new WriteReservationId(UUID.randomUUID());
  }

  private static RowCursor cursorOf(List<StoredRow> rows) {
    Iterator<StoredRow> iterator = rows.iterator();
    return new RowCursor() {
      @Override
      public Optional<StoredRow> next() {
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
      }

      @Override
      public void close() {}
    };
  }
}
