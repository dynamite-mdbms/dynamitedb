package dynamitedb.sm.facade;

import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.draft.IndexAdmin;
import dynamitedb.sm.draft.IndexKind;
import dynamitedb.sm.draft.IndexMetadata;
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
 * SM1 / Jason Edward Salim: read, prepared-write and index-admin facade template.
 *
 * <p>QP obtains CCM permission before access and logs prepared changes before applyWrite.
 * Delegations terminate at TODO stubs; bootstrap alone wires concrete classes across modules.
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
  public RowCursor openTableScan(String table, TransactionId tx) {
    // TODO(SM1/SM4): Resolve stable table ID/schema and open a HeapRowCursor after CCM permission.
    // Agree on internal cursor assembly with heap/buffer/serializer owners.
    throw new UnsupportedOperationException("TODO(SM1): open a table scan");
  }

  @Override
  public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
    // TODO(SM1/SM5): Obtain matching RIDs and assemble an IndexRowCursor with the table schema.
    throw new UnsupportedOperationException("TODO(SM1): open an index scan");
  }

  @Override
  public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
    // TODO(SM1/SM3/SM4): Read the image, validate incarnation and decode using the table schema.
    throw new UnsupportedOperationException("TODO(SM1): fetch a stored row");
  }

  @Override
  public PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
    // TODO(SM1/SM3): Reserve through records.reserve and capture immutable before/after images.
    // Preparation must not apply the insert to heap or indexes.
    throw new UnsupportedOperationException("TODO(SM1): prepare an insert");
  }

  @Override
  public PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
    // TODO(SM1/SM3): Capture immutable images and a reservation without changing the stored row.
    throw new UnsupportedOperationException("TODO(SM1): prepare an update");
  }

  @Override
  public PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
    // TODO(SM1): Reserve this live RID with its before image and an absent after image.
    throw new UnsupportedOperationException("TODO(SM1): prepare a delete");
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
}
