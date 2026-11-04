package dynamitedb.contracts.transaction;

/** Granularity of an access resource; the baseline is table-level. */
public enum ResourceKind {
  /** A whole table, with no record ID. */
  TABLE,
  /** A record whose table ID matches the resource's table ID. */
  RECORD
}
