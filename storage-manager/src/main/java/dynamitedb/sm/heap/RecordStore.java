package dynamitedb.sm.heap;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.index.IndexManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.Objects;

/** Operasi record fisik di bawah facade SM (SM-04 s.d. SM-07). */
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
    // TODO: cek inkarnasi slot lalu baca image baris
    return new RecordImage(false, ImmutableBytes.copyOf(new byte[0]));
  }

  public RecordId reserve(String table) {
    // TODO: reservasi slot tanpa menampilkan baris baru
    return heap.allocateSlot(table);
  }

  public void apply(PreparedWrite prepared, LogSequenceNumber lsn) {
    // TODO: cocokkan LSN dengan reservasi, terapkan ke heap dan index
  }

  public void cancel(PreparedWrite prepared) {
    // TODO: lepas reservasi yang belum diterapkan, yang sudah diterapkan di-rollback FRM
  }

  public void applyRecovery(RecoveryMutation mutation, LogSequenceNumber lsn) {
    // TODO: redo/undo idempotent dengan pageLSN dan index tetap konsisten
  }
}
