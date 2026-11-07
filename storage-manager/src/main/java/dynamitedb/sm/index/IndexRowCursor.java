package dynamitedb.sm.index;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.storage.DbmsException;
import dynamitedb.contracts.storage.ErrorCode;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
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

  private volatile boolean closed; // Penanda cursor sudah ditutup; volatile agar terlihat antar thread

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
    if (closed) { // Pelanggaran urutan operasi
      throw new DbmsException(ErrorCode.INVALID_STATE, "IndexRowCursor sudah ditutup");
    }
    while (rids.hasNext()) { // Lewati RID mati
      RecordId rid = rids.next();
      RecordImage image = records.read(rid).orElse(null);
      if (image == null || !image.exists()) {
        continue; // RID mati
      }
      Row row = serializer.decode(image.encoded(), schema);
      return Optional.of(new StoredRow(rid, row));
    }
    return Optional.empty();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    while (rids.hasNext()) { // Lepas sisa referensi RID
      rids.next();
    }
  }
}
