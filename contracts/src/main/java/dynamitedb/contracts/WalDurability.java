package dynamitedb.contracts;

/** Storage durability gate. Real implementations force WAL through the requested page LSN. */
public interface WalDurability {
  void ensureDurable(LogSequenceNumber lsn);
}
