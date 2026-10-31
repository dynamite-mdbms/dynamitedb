package dynamitedb.sm.index;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.RowCursor;
import dynamitedb.sm.draft.Schema;
import dynamitedb.sm.draft.StoredRow;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/** SM5 cursor template: resolves index RIDs through SM1 and decodes rows with the table schema. */
public final class IndexRowCursor implements RowCursor {
  private final RecordStore records;
  private final Iterator<RecordId> rids;
  private final Schema schema;
  private final BufferPool buffer;
  private final RecordSerializer serializer;

  public IndexRowCursor(
      RecordStore records,
      Iterator<RecordId> rids,
      Schema schema,
      BufferPool buffer,
      RecordSerializer serializer) {
    this.records = Objects.requireNonNull(records, "records");
    this.rids = Objects.requireNonNull(rids, "rids");
    this.schema = Objects.requireNonNull(schema, "schema");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
  }

  @Override
  public Optional<StoredRow> next() {
    // TODO(SM5/SM1/SM3): Read/decode the next live RID and preserve its physical identity.
    throw new UnsupportedOperationException("TODO(SM5): advance an index row cursor");
  }

  @Override
  public void close() {
    // TODO(SM5/SM1/SM2): Release traversal resources once and reject reads after closure.
    throw new UnsupportedOperationException("TODO(SM5): close an index row cursor");
  }
}
