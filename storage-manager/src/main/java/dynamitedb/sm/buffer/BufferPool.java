package dynamitedb.sm.buffer;

import dynamitedb.sm.draft.LogSequenceNumber;
import dynamitedb.sm.draft.WalDurability;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** SM2 / Raynard Fausta: pinning, dirty pages, flushing and eviction (SM-01, SM-12-SM-13). */
public final class BufferPool {
  private final DiskManager disk;
  private final WalDurability durability;
  private final Map<Long, Page> pages = new HashMap<>();

  public BufferPool(DiskManager disk, WalDurability durability) {
    this.disk = Objects.requireNonNull(disk, "disk");
    this.durability = Objects.requireNonNull(durability, "durability");
  }

  public Page pin(long pageId) {
    // TODO(SM2): Load/cache the page and acquire a pin; agree on capacity and replacement policy.
    // Dirty eviction must use the same WAL-before-data flush path as explicit flushes.
    throw new UnsupportedOperationException("TODO(SM2): pin a cached page");
  }

  public void unpin(Page page) {
    // TODO(SM2): Release one pin for this cached page and reject invalid/double-unpin operations.
    throw new UnsupportedOperationException("TODO(SM2): unpin a cached page");
  }

  public void markDirty(Page page, LogSequenceNumber lsn) {
    // TODO(SM2/SM1): Track dirty state and pageLSN without overtaking outstanding slot mutations.
    throw new UnsupportedOperationException("TODO(SM2): mark a page dirty");
  }

  public void flush(Page page) {
    // TODO(SM2/FR2): Ensure durability.ensureDurable(page.getPageLsn()) before disk.writePage.
    // Keep dirty state on failure; clearing it requires the agreed durable synchronization.
    throw new UnsupportedOperationException("TODO(SM2): flush a page through the WAL gate");
  }

  public void flushDirtyThrough(LogSequenceNumber lsn) {
    // TODO(SM2/FRM): Define the through-LSN boundary and durably sync all required dirty pages.
    throw new UnsupportedOperationException("TODO(SM2): flush dirty pages through an LSN");
  }
}
