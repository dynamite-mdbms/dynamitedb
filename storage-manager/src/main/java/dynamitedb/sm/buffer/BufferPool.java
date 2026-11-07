package dynamitedb.sm.buffer;

import dynamitedb.contracts.api.WalDurability;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ImmutableBytes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class BufferPool {
  public static final int DEFAULT_CAPACITY = 128;
  private final DiskManager disk;
  private final WalDurability durability;
  private final int capacity;
  private boolean callbackActive;
  private final Map<Long, Page> pages = new LinkedHashMap<>();

  public BufferPool(DiskManager disk, WalDurability durability) {
    this(disk, durability, DEFAULT_CAPACITY);
  }

  public BufferPool(DiskManager disk, WalDurability durability, int capacity) {
    this.disk = Objects.requireNonNull(disk, "disk");
    this.durability = Objects.requireNonNull(durability, "durability");
    if (capacity <= 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Kapasitas buffer harus positif");
    }
    this.capacity = capacity;
  }

  public synchronized Page pin(long pageId) {
    requireOutsideCallback();
    var cached = pages.get(pageId);
    if (cached != null) {
      cached.pin();
      pages.remove(pageId);
      pages.put(pageId, cached);
      return cached;
    }
    var victim = findVictim();
    var page = disk.loadPage(pageId);
    evict(victim);
    page.attach(this);
    page.pin();
    pages.put(pageId, page);
    return page;
  }

  public synchronized Page allocatePage() {
    requireOutsideCallback();
    var victim = findVictim();
    if (victim != null) {
      flushForEviction(victim);
    }
    long id = disk.appendPage();
    var page =
        new Page(
            id, ImmutableBytes.copyOf(new byte[DiskManager.PAGE_SIZE]), new LogSequenceNumber(0));
    if (victim != null) {
      pages.remove(victim.getId());
    }
    page.attach(this);
    page.pin();
    pages.put(id, page);
    return page;
  }

  public synchronized void unpin(Page page) {
    requireOutsideCallback();
    requireResident(page);
    page.requireIdle();
    page.unpin();
  }

  public synchronized void markDirty(Page page, LogSequenceNumber lsn) {
    requireOutsideCallback();
    requirePinned(page);
    page.setPageLsnInternal(lsn);
  }

  public synchronized void updatePage(Page page, ImmutableBytes bytes, LogSequenceNumber lsn) {
    requireOutsideCallback();
    requirePinned(page);
    page.updateDataInternal(bytes, lsn);
  }

  public synchronized void mutate(
      Page page, UnaryOperator<ImmutableBytes> change, Supplier<LogSequenceNumber> appendWal) {
    requireOutsideCallback();
    requirePinned(page);
    callbackActive = true;
    try {
      page.mutateInternal(change, appendWal);
    } finally {
      callbackActive = false;
    }
  }

  public synchronized void flush(Page page) {
    requireOutsideCallback();
    flushInternal(page);
  }

  private void flushInternal(Page page) {
    requireResident(page);
    synchronized (page) {
      page.requireIdle();
      if (!page.isDirty()) {
        return;
      }
      var lsn = page.getPageLsn();
      var data = page.getData();
      callbackActive = true;
      try {
        durability.ensureDurable(lsn);
      } finally {
        callbackActive = false;
      }
      disk.writePage(page.getId(), data, lsn);
      disk.sync();
      page.markClean(data, lsn);
    }
  }

  public synchronized void flushDirtyThrough(LogSequenceNumber lsn) {
    requireOutsideCallback();
    if (Objects.requireNonNull(lsn, "lsn").value() < 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Batas LSN tidak boleh negatif");
    }
    for (var page : pages.values()) {
      synchronized (page) {
        if (page.isDirty() && page.getPageLsn().value() <= lsn.value()) {
          flushInternal(page);
        }
      }
    }
  }

  /** Discard semua frame tanpa flush, dipakai SM1 saat generation switch. */
  public synchronized void discardAll() {
    requireOutsideCallback();
    for (var page : pages.values()) {
      if (page.getPinCount() != 0) {
        throw new DbmsException(
            ErrorCode.INVALID_STATE, "Generation tidak bisa di-switch, page masih di-pin");
      }
    }
    pages.clear();
  }

  private Page findVictim() {
    if (pages.size() < capacity) {
      return null;
    }
    for (var page : pages.values()) {
      if (page.getPinCount() == 0) {
        return page;
      }
    }
    throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Buffer penuh: semua frame sedang di-pin");
  }

  private void evict(Page victim) {
    if (victim != null) {
      flushForEviction(victim);
      pages.remove(victim.getId());
    }
  }

  private void flushForEviction(Page page) {
    flushInternal(page);
    if (page.isDirty()) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Halaman berubah selama flush; eviction harus dicoba ulang");
    }
  }

  private void requireResident(Page page) {
    Objects.requireNonNull(page, "page");
    if (pages.get(page.getId()) != page) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Halaman bukan frame resident pool ini");
    }
  }

  private void requireOutsideCallback() {
    if (callbackActive) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Callback tidak boleh masuk kembali ke pool");
    }
  }

  private void requirePinned(Page page) {
    requireResident(page);
    if (page.getPinCount() == 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Mutasi memerlukan pin halaman");
    }
  }
}
