package dynamitedb.sm.heap;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.Page;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/** Menelusuri heap dan mengembalikan baris beserta RID-nya (SM-04). */
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
    // TODO: telusuri slot hidup, decode baris, lepas pin selama traversal
    return Optional.empty();
  }

  @Override
  public void close() {
    // TODO: lepas resource sekali, tolak next setelah close
  }
}
