package dynamitedb.sm.facade;

import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.util.Objects;
import java.util.Optional;

/** SM facade untuk QP: read, two-phase write, create index; delegasi ke RecordStore. */
public final class StorageManagerImpl implements TupleReader, WriteStore, IndexAdmin {
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
    Objects.requireNonNull(tx, "tx");
    return records.openScan(Objects.requireNonNull(table, "table"));
  }

  @Override
  public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    return records.openIndexScan(index, predicate);
  }

  @Override
  public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    return records.fetch(rid);
  }

  @Override
  public PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
    return records.prepareInsert(tx, table, row);
  }

  @Override
  public PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
    return records.prepareUpdate(tx, rid, row);
  }

  @Override
  public PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
    return records.prepareDelete(tx, rid);
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
    records.createIndex(
        new IndexMetadata(
            Objects.requireNonNull(name, "name"),
            Objects.requireNonNull(table, "table"),
            Objects.requireNonNull(column, "column"),
            Objects.requireNonNull(kind, "kind")));
  }
}
