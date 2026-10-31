package dynamitedb.sm.buffer;

import dynamitedb.sm.draft.ImmutableBytes;
import dynamitedb.sm.draft.LogSequenceNumber;
import java.util.Objects;

/** SM2 / Raynard Fausta: cached page state; physical layout is agreed with SM3. */
public final class Page {
  private final long id;
  private ImmutableBytes bytes;
  private boolean dirty;
  private LogSequenceNumber pageLsn;

  public Page(long id, ImmutableBytes bytes, LogSequenceNumber pageLsn) {
    this.id = id;
    this.bytes = Objects.requireNonNull(bytes, "bytes");
    this.pageLsn = Objects.requireNonNull(pageLsn, "pageLsn");
  }

  public long id() {
    return id;
  }

  public ImmutableBytes bytes() {
    return bytes;
  }

  public boolean isDirty() {
    return dirty;
  }

  public LogSequenceNumber getPageLsn() {
    return pageLsn;
  }

  public void replaceBytes(ImmutableBytes bytes, LogSequenceNumber lsn) {
    // TODO(SM2/SM3): Apply an agreed page image under the page latch and update dirty/LSN state.
    // An earlier outstanding mutation on another slot must not be skipped by pageLSN advancement.
    throw new UnsupportedOperationException("TODO(SM2): replace cached page contents");
  }

  public void setPageLsn(LogSequenceNumber lsn) {
    // TODO(SM2/SM1): Enforce ordering with all outstanding mutations before changing pageLSN.
    throw new UnsupportedOperationException("TODO(SM2): advance pageLSN safely");
  }
}
