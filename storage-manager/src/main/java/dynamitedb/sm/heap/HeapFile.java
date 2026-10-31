package dynamitedb.sm.heap;

import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.draft.LogSequenceNumber;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.RecordImage;
import java.util.List;
import java.util.Objects;

/** SM1 / Jason Edward Salim: heap pages and slot reservations beneath RecordStore. */
public final class HeapFile {
  private final BufferPool buffer;

  public HeapFile(BufferPool buffer) {
    this.buffer = Objects.requireNonNull(buffer, "buffer");
  }

  public List<Long> pageNumbers(long tableId) {
    // TODO(SM1/SM2): Enumerate heap pages and agree on table/page-to-internal-page-ID mapping.
    throw new UnsupportedOperationException("TODO(SM1): enumerate heap pages");
  }

  public RecordId allocateSlot(long tableId) {
    // TODO(SM1/SM3): Reserve a slot using the agreed page layout; advance its incarnation on reuse.
    // A reservation must not expose an inserted row before the matching logged write is applied.
    throw new UnsupportedOperationException("TODO(SM1): reserve a heap slot");
  }

  public RecordImage readImage(RecordId rid) {
    // TODO(SM1/SM2/SM3): Pin the page, validate slot identity and copy the serialized image.
    throw new UnsupportedOperationException("TODO(SM1): read a heap slot image");
  }

  public void writeImage(RecordId rid, RecordImage image, LogSequenceNumber lsn) {
    // TODO(SM1/SM2): Apply the image and coordinate dirty/pageLSN state under the page latch.
    // Respect slot incarnation so a stale recovery record cannot overwrite a reused slot.
    throw new UnsupportedOperationException("TODO(SM1): write a heap slot image");
  }

  public void cancelSlot(RecordId rid) {
    // TODO(SM1): Release only an unapplied reservation without corrupting slot incarnation state.
    throw new UnsupportedOperationException("TODO(SM1): cancel a heap slot reservation");
  }
}
