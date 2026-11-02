package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.LogSequenceNumber;

/** Memastikan WAL sudah durable sebelum halaman dirty ditulis ke disk. */
public interface WalDurability {
  void ensureDurable(LogSequenceNumber lsn);
}
