package dynamitedb.contracts.transaction;

/** Outcome of an access request; only {@link #GRANTED} permits the operation. */
public enum AccessState {
  /** Access was granted. */
  GRANTED,
  /** Access is pending; this is not permission to proceed. */
  WAITING,
  /** Access was denied. */
  ABORTED
}
