package dynamitedb.sm.heap;

import dynamitedb.sm.buffer.Page;
import dynamitedb.sm.draft.RecordId;

/** SM1 / Jason Edward Salim: heap page access and slot allocation beneath RecordStore. */
public final class HeapFile {
  public Page readPage(long pageId) {
    // TODO(SM1/SM2): Resolve the agreed page source and mapping from table/page identity to page
    // ID.
    throw new UnsupportedOperationException("TODO(SM1): read a heap page");
  }

  public RecordId allocateSlot(String table) {
    // TODO(SM1/SM3/SM4): Resolve the table and reserve a slot using the agreed physical page
    // layout.
    // Advance the slot incarnation on reuse; a reservation must not expose a row before apply.
    throw new UnsupportedOperationException("TODO(SM1): allocate a heap slot");
  }

  public void writePage(Page page) {
    // TODO(SM1/SM2): Route page changes through buffering and the WAL-before-data durability gate.
    throw new UnsupportedOperationException("TODO(SM1): write a heap page");
  }
}
