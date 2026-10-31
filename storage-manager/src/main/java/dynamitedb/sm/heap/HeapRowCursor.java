package dynamitedb.sm.heap;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.draft.RowCursor;
import dynamitedb.sm.draft.Schema;
import dynamitedb.sm.draft.StoredRow;
import dynamitedb.sm.draft.TransactionId;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Objects;
import java.util.Optional;

/** SM1 / Jason Edward Salim: heap traversal retaining physical RIDs and transaction context. */
public final class HeapRowCursor implements RowCursor {
  private final TransactionId tx;
  private final long tableId;
  private final Schema schema;
  private final HeapFile heap;
  private final BufferPool buffer;
  private final RecordSerializer serializer;

  public HeapRowCursor(
      TransactionId tx,
      long tableId,
      Schema schema,
      HeapFile heap,
      BufferPool buffer,
      RecordSerializer serializer) {
    this.tx = Objects.requireNonNull(tx, "tx");
    this.tableId = tableId;
    this.schema = Objects.requireNonNull(schema, "schema");
    this.heap = Objects.requireNonNull(heap, "heap");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
  }

  @Override
  public Optional<StoredRow> next() {
    // TODO(SM1/SM2/SM3): Traverse live slots, decode using schema order and retain physical RIDs.
    // Release page pins during traversal; Optional.empty means exhaustion.
    throw new UnsupportedOperationException("TODO(SM1): advance a heap row cursor");
  }

  @Override
  public void close() {
    // TODO(SM1/SM2): Release traversal resources once and reject reads after closure.
    throw new UnsupportedOperationException("TODO(SM1): close a heap row cursor");
  }
}
