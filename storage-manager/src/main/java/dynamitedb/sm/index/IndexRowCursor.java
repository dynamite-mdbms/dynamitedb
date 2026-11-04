package dynamitedb.sm.index;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/** Mengembalikan baris dari RID hasil pencarian index (SM-11). */
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
    // TODO: baca dan decode RID berikutnya yang masih hidup
    return Optional.empty();
  }

  @Override
  public void close() {
    // TODO: lepas resource sekali, tolak next setelah close
  }
}
