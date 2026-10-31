package dynamitedb.sm.heap;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.sm.buffer.Page;

/** Akses halaman heap dan alokasi slot di bawah RecordStore. */
public final class HeapFile {
  public Page readPage(long pageId) {
    // TODO: pemetaan tabel dan halaman ke page ID
    return new Page(pageId, ImmutableBytes.copyOf(new byte[0]), new LogSequenceNumber(0));
  }

  public RecordId allocateSlot(String table) {
    // TODO: reservasi slot sesuai format halaman, naikkan inkarnasi saat slot dipakai ulang
    return new RecordId(0, 0, 0, 0);
  }

  public void writePage(Page page) {
    // TODO: tulis lewat BufferPool dan gerbang WAL
  }
}
