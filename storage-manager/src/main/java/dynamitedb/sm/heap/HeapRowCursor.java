package dynamitedb.sm.heap;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.Page;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/** Lazy per-page heap scan (SM-04); tidak pegang pin di antara next(). */
public final class HeapRowCursor implements RowCursor {
  private final HeapFile heap;
  private final long tableId;
  private final Schema schema;
  private final Iterator<Page> pageIterator;
  private final BufferPool buffer;
  private final RecordSerializer serializer;

  private ImmutableBytes currentData;
  private long currentPageId;
  private int currentSlotCount;
  private int nextSlot;
  private boolean closed;

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
  public synchronized Optional<StoredRow> next() {
    if (closed) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "HeapRowCursor sudah di-close");
    }
    while (true) {
      if (currentData == null && !loadNextPage()) {
        return Optional.empty();
      }
      while (nextSlot < currentSlotCount) {
        int slot = nextSlot++;
        long version = serializer.getSlotVersion(currentData, slot);
        RecordImage image = serializer.readSlot(currentData, slot, version);
        if (image.exists()) {
          RecordId rid = new RecordId(tableId, currentPageId, slot, version);
          return Optional.of(new StoredRow(rid, serializer.decode(image, schema)));
        }
      }
      currentData = null;
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    currentData = null;
  }

  private boolean loadNextPage() {
    if (!pageIterator.hasNext()) {
      return false;
    }
    Page page = pageIterator.next();
    try {
      currentPageId = page.getId();
      currentData = page.getData();
    } finally {
      buffer.unpin(page);
    }
    currentSlotCount = serializer.getSlotCount(currentData);
    nextSlot = 0;
    return true;
  }
}
