package dynamitedb.sm.buffer;

import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ImmutableBytes;
import java.util.Objects;

/** Halaman di buffer beserta pageLSN, format fisiknya disepakati dengan SM3. */
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
    // TODO: jaga urutan pageLSN terhadap mutasi slot yang belum selesai
    this.pageLsn = Objects.requireNonNull(lsn, "lsn");
  }
}
