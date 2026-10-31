package dynamitedb.sm.draft;

/** Temporary FR2-owned durability gate used before every dirty-page write, including eviction. */
public interface WalDurability {
  /** Successful return must mean WAL is durable through lsn rather than merely buffered. */
  void ensureDurable(LogSequenceNumber lsn);
}
