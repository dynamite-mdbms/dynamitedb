package dynamitedb.sm.buffer;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ImmutableBytes;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class Page {
  private final long id;
  private ImmutableBytes data;
  private boolean dirty;
  private LogSequenceNumber pageLsn;
  private int pinCount;
  private boolean mutating;
  private volatile BufferPool owner;

  public Page(long id, ImmutableBytes data, LogSequenceNumber pageLsn) {
    if (id < 0) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "ID halaman tidak boleh negatif");
    }
    validateData(data);
    validateLsn(pageLsn);
    this.id = id;
    this.data = data;
    this.pageLsn = pageLsn;
  }

  public long getId() {
    return id;
  }

  public synchronized ImmutableBytes getData() {
    return data;
  }

  public synchronized boolean isDirty() {
    return dirty;
  }

  public synchronized int getPinCount() {
    return pinCount;
  }

  public synchronized LogSequenceNumber getPageLsn() {
    return pageLsn;
  }

  public void setPageLsn(LogSequenceNumber lsn) {
    while (true) {
      var pool = owner;
      if (pool != null) {
        pool.markDirty(this, lsn);
        return;
      }
      synchronized (this) {
        if (owner == null) {
          setPageLsnInternal(lsn);
          return;
        }
      }
    }
  }

  synchronized void setPageLsnInternal(LogSequenceNumber lsn) {
    requireIdle();
    requireOrdered(lsn);
    this.pageLsn = lsn;
    dirty = true;
  }

  public void updateData(ImmutableBytes bytes, LogSequenceNumber lsn) {
    while (true) {
      var pool = owner;
      if (pool != null) {
        pool.updatePage(this, bytes, lsn);
        return;
      }
      synchronized (this) {
        if (owner == null) {
          updateDataInternal(bytes, lsn);
          return;
        }
      }
    }
  }

  synchronized void updateDataInternal(ImmutableBytes bytes, LogSequenceNumber lsn) {
    requireIdle();
    validateData(bytes);
    requireOrdered(lsn);
    data = bytes;
    pageLsn = lsn;
    dirty = true;
  }

  public void mutate(UnaryOperator<ImmutableBytes> change, Supplier<LogSequenceNumber> appendWal) {
    while (true) {
      var pool = owner;
      if (pool != null) {
        pool.mutate(this, change, appendWal);
        return;
      }
      synchronized (this) {
        if (owner == null) {
          mutateInternal(change, appendWal);
          return;
        }
      }
    }
  }

  synchronized void attach(BufferPool pool) {
    requireIdle();
    if (owner != null) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Halaman sudah memiliki pool");
    }
    owner = Objects.requireNonNull(pool, "pool");
  }

  synchronized void mutateInternal(
      UnaryOperator<ImmutableBytes> change, Supplier<LogSequenceNumber> appendWal) {
    requireIdle();
    Objects.requireNonNull(change, "change");
    Objects.requireNonNull(appendWal, "appendWal");
    mutating = true;
    try {
      var updated = change.apply(data);
      validateData(updated);
      var lsn = appendWal.get();
      requireOrdered(lsn);
      data = updated;
      pageLsn = lsn;
      dirty = true;
    } finally {
      mutating = false;
    }
  }

  synchronized void pin() {
    if (pinCount == Integer.MAX_VALUE) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Jumlah pin melebihi batas");
    }
    pinCount++;
  }

  synchronized void unpin() {
    if (pinCount == 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Halaman sudah tidak di-pin: " + id);
    }
    pinCount--;
  }

  synchronized void markClean(ImmutableBytes writtenData, LogSequenceNumber writtenLsn) {
    requireIdle();
    if (data == writtenData && pageLsn.equals(writtenLsn)) {
      dirty = false;
    }
  }

  synchronized void requireIdle() {
    if (mutating) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Mutasi halaman belum selesai: " + id);
    }
  }

  private void requireOrdered(LogSequenceNumber lsn) {
    validateLsn(lsn);
    if (lsn.value() < pageLsn.value()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "pageLSN tidak boleh mundur: " + id);
    }
  }

  private static void validateLsn(LogSequenceNumber lsn) {
    if (Objects.requireNonNull(lsn, "lsn").value() < 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "LSN tidak boleh negatif");
    }
  }

  private static void validateData(ImmutableBytes data) {
    if (Objects.requireNonNull(data, "data").copy().length != DiskManager.PAGE_SIZE) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Ukuran payload halaman tidak valid");
    }
  }
}
