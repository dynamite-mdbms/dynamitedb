package dynamitedb.sm.heap;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.Page;
import dynamitedb.sm.draft.RowCursor;
import dynamitedb.sm.draft.Schema;
import dynamitedb.sm.draft.StoredRow;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/** SM1 / Jason Edward Salim: heap traversal retaining the physical identity of decoded rows. */
public final class HeapRowCursor implements RowCursor {
  private final HeapFile heap;
  private final long tableId;
  private final Schema schema;
  private final Iterator<Page> pageIterator;
  private final BufferPool buffer;
  private final RecordSerializer serializer;

  public HeapRowCursor(
      HeapFile heap,
      long tableId,
      Schema schema,
      Iterator<Page> pageIterator,
      BufferPool buffer,
      RecordSerializer serializer) {
    this.heap = Objects.requireNonNull(heap, "heap");
    this.tableId = tableId;
    this.schema = Objects.requireNonNull(schema, "schema");
    this.pageIterator = Objects.requireNonNull(pageIterator, "pageIterator");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
  }

  @Override
  public Optional<StoredRow> next() {
    // TODO(SM1/SM2/SM3): Traverse live slots in pageIterator, decode rows and retain physical RIDs.
    // Release pins during traversal; Optional.empty means exhaustion rather than SQL NULL.
    throw new UnsupportedOperationException("TODO(SM1): advance a heap row cursor");
  }

  @Override
  public void close() {
    // TODO(SM1/SM2): Release traversal resources once and reject reads after closure.
    throw new UnsupportedOperationException("TODO(SM1): close a heap row cursor");
  }
}
