package dynamitedb.sm.heap;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.draft.IndexPredicate;
import dynamitedb.sm.draft.LogSequenceNumber;
import dynamitedb.sm.draft.PreparedWrite;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.RecordImage;
import dynamitedb.sm.draft.RecoveryMutation;
import dynamitedb.sm.draft.Row;
import dynamitedb.sm.draft.RowCursor;
import dynamitedb.sm.draft.StoredRow;
import dynamitedb.sm.draft.TransactionId;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Objects;
import java.util.Optional;

/** SM1 / Jason Edward Salim: heap, serialization and index coordination (SM-04-SM-07). */
public final class RecordStore {
  private final HeapFile heap;
  private final BufferPool buffer;
  private final RecordSerializer serializer;
  private final CatalogManager catalog;
  private final IndexManager indexes;

  public RecordStore(
      HeapFile heap,
      BufferPool buffer,
      RecordSerializer serializer,
      CatalogManager catalog,
      IndexManager indexes) {
    this.heap = Objects.requireNonNull(heap, "heap");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
  }

  public RowCursor scanTable(String table, TransactionId tx) {
    // TODO(SM1/SM4): Resolve stable table ID/schema and open a closeable HeapRowCursor.
    throw new UnsupportedOperationException("TODO(SM1): open a heap scan");
  }

  public RowCursor scanIndex(String index, IndexPredicate predicate, TransactionId tx) {
    // TODO(SM1/SM5): Obtain matching RIDs and open an IndexRowCursor with this RecordStore.
    throw new UnsupportedOperationException("TODO(SM1): open an index scan");
  }

  public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
    // TODO(SM1/SM3/SM4): Validate RID incarnation and decode the live row using its table schema.
    throw new UnsupportedOperationException("TODO(SM1): fetch a stored row");
  }

  public PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
    // TODO(SM1/SM3): Validate/encode the row and reserve a RID with immutable before/after images.
    // Preparation must not apply the insert to heap or indexes.
    throw new UnsupportedOperationException("TODO(SM1): prepare an insert");
  }

  public PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
    // TODO(SM1): Capture immutable images and a reservation without changing the stored row.
    throw new UnsupportedOperationException("TODO(SM1): prepare an update");
  }

  public PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
    // TODO(SM1): Reserve this live RID and capture its before image with an absent after image.
    throw new UnsupportedOperationException("TODO(SM1): prepare a delete");
  }

  public void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn) {
    // TODO(SM1/CCM/FRM): Validate the reservation and ensure this LSN belongs to this exact write.
    // TODO(SM1/SM2/SM5): Apply heap/index changes consistently and coordinate pageLSN ordering.
    throw new UnsupportedOperationException("TODO(SM1): apply a matching logged write");
  }

  public void cancelPrepared(PreparedWrite prepared) {
    // TODO(SM1): Release an unapplied reservation; an applied write requires FRM rollback.
    throw new UnsupportedOperationException("TODO(SM1): cancel a prepared write");
  }

  public RecordImage readImage(RecordId rid) {
    // TODO(SM1/SM3): Read the canonical immutable image without exposing buffers to FRM.
    throw new UnsupportedOperationException("TODO(SM1): read a recovery image");
  }

  public void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
    // TODO(SM1/SM2/SM5/FRM): Apply idempotent redo/undo with incarnation checks and index
    // consistency.
    // FRM internal LogRecord types must not enter this boundary.
    throw new UnsupportedOperationException("TODO(SM1): apply a recovery mutation");
  }
}
