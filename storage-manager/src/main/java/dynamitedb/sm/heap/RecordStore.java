package dynamitedb.sm.heap;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.draft.LogSequenceNumber;
import dynamitedb.sm.draft.PreparedWrite;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.RecordImage;
import dynamitedb.sm.draft.RecoveryMutation;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Objects;

/** SM1 / Jason Edward Salim: physical record services beneath the storage facade (SM-04-SM-07). */
public final class RecordStore {
  private final HeapFile heap;
  private final BufferPool buffer;
  private final RecordSerializer serializer;
  private final IndexManager indexes;

  public RecordStore(
      HeapFile heap, BufferPool buffer, RecordSerializer serializer, IndexManager indexes) {
    this.heap = Objects.requireNonNull(heap, "heap");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
  }

  public RecordImage read(RecordId rid) {
    // TODO(SM1/SM2/SM3): Validate slot incarnation and read the canonical immutable row image.
    throw new UnsupportedOperationException("TODO(SM1): read a stored record image");
  }

  public RecordId reserve(String table) {
    // TODO(SM1/SM4): Resolve the table identity and reserve a heap slot without exposing a new row.
    throw new UnsupportedOperationException("TODO(SM1): reserve a record identity");
  }

  public void apply(PreparedWrite prepared, LogSequenceNumber lsn) {
    // TODO(SM1/CCM/FRM): Validate the reservation and match this LSN to this exact prepared write.
    // TODO(SM1/SM2/SM5): Apply heap/index changes consistently and coordinate pageLSN ordering.
    throw new UnsupportedOperationException("TODO(SM1): apply a matching logged write");
  }

  public void cancel(PreparedWrite prepared) {
    // TODO(SM1): Release only an unapplied reservation; an applied write requires FRM rollback.
    throw new UnsupportedOperationException("TODO(SM1): cancel a prepared reservation");
  }

  public void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
    // TODO(SM1/SM2/SM5/FRM): Apply idempotent redo/undo with incarnation checks and consistent
    // indexes.
    // FRM internal LogRecord types must not enter this boundary.
    throw new UnsupportedOperationException("TODO(SM1): apply a recovery mutation");
  }
}
