package dynamitedb.contracts.transaction;

/** The requested permission; baseline access resources use table-level locking. */
public enum AccessMode {
  /** Requests shared permission for reads. */
  READ,
  /** Requests exclusive permission for writes. */
  WRITE
}
