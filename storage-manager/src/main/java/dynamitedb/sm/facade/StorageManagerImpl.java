package dynamitedb.sm.facade;

import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.draft.IndexAdmin;
import dynamitedb.sm.draft.IndexKind;
import dynamitedb.sm.draft.IndexPredicate;
import dynamitedb.sm.draft.LogSequenceNumber;
import dynamitedb.sm.draft.PreparedWrite;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.Row;
import dynamitedb.sm.draft.RowCursor;
import dynamitedb.sm.draft.StoredRow;
import dynamitedb.sm.draft.TransactionId;
import dynamitedb.sm.draft.TupleReader;
import dynamitedb.sm.draft.WriteStore;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.util.Objects;
import java.util.Optional;

/**
 * SM1 / Jason Edward Salim: facade wiring for read, prepared-write and index-admin ports.
 *
 * <p>Delegates terminate at TODO stubs. QP obtains CCM permission before access and logs prepared
 * changes before calling applyWrite. Bootstrap alone exposes this concrete class across modules.
 */
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
  public RowCursor scanTable(String table, TransactionId tx) {
    return records.scanTable(table, tx);
  }

  @Override
  public RowCursor scanIndex(String index, IndexPredicate predicate, TransactionId tx) {
    return records.scanIndex(index, predicate, tx);
  }

  @Override
  public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
    return records.fetch(rid, tx);
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
    records.applyWrite(prepared, lsn);
  }

  @Override
  public void cancelPrepared(PreparedWrite prepared) {
    records.cancelPrepared(prepared);
  }

  @Override
  public void createIndex(String index, String table, String column, IndexKind kind) {
    indexes.createIndex(index, table, column, kind);
  }
}
