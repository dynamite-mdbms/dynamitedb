package dynamitedb.sm.buffer;

import dynamitedb.contracts.api.WalDurability;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Mengelola pin/unpin, halaman dirty, flush, dan eviction (SM-12, SM-13). */
public final class BufferPool {
  private final DiskManager disk;
  private final WalDurability durability;
  private final Map<Long, Page> pages = new HashMap<>();

  public BufferPool(DiskManager disk, WalDurability durability) {
    this.disk = Objects.requireNonNull(disk, "disk");
    this.durability = Objects.requireNonNull(durability, "durability");
  }

  public Page pin(long pageId) {
    // TODO: cache halaman dan pin, tentukan kapasitas dan kebijakan penggantian
    // eviction halaman dirty juga harus lewat gerbang WAL
    return pages.computeIfAbsent(
        pageId, id -> new Page(id, disk.readPage(id), new LogSequenceNumber(0)));
  }

  public void unpin(Page page) {
    // TODO: lepas satu pin, tolak unpin ganda
  }

  public void markDirty(Page page, LogSequenceNumber lsn) {
    // TODO: catat dirty dan pageLSN tanpa melewati mutasi slot sebelumnya
    page.setPageLsn(lsn);
  }

  public void flush(Page page) {
    // TODO: panggil ensureDurable(pageLSN) sebelum disk.writePage, status dirty tetap jika gagal
    durability.ensureDurable(page.getPageLsn());
  }

  public void flushDirtyThrough(LogSequenceNumber lsn) {
    // TODO: flush semua halaman dirty sampai LSN untuk checkpoint FRM
  }
}
