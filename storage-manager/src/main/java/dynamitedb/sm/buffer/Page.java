package dynamitedb.sm.buffer;

import dynamitedb.sm.draft.ImmutableBytes;
import dynamitedb.sm.draft.LogSequenceNumber;
import java.util.Objects;

/** SM2 / Raynard Fausta: cached page state; physical layout is agreed with SM3. */
public final class Page {
  private final long id;
  private ImmutableBytes data;
  private boolean dirty;
  private LogSequenceNumber pageLsn;

  public Page(long id, ImmutableBytes data, LogSequenceNumber pageLsn) {
    this.id = id;
    this.data = Objects.requireNonNull(data, "data");
    this.pageLsn = Objects.requireNonNull(pageLsn, "pageLsn");
  }

  public LogSequenceNumber getPageLsn() {
    return pageLsn;
  }

  public void setPageLsn(LogSequenceNumber lsn) {
    // TODO(SM2/SM1): Track dirty state and enforce ordering with all outstanding page mutations.
    // An earlier outstanding mutation on another slot must not be skipped by pageLSN advancement.
    throw new UnsupportedOperationException("TODO(SM2): advance pageLSN safely");
  }
}
