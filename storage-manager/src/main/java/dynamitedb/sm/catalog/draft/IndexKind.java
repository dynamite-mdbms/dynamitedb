package dynamitedb.sm.catalog.draft;

/** Temporary index kinds: HASH supports equality; B_PLUS_TREE also supports ordered ranges. */
public enum IndexKind {
  HASH,
  B_PLUS_TREE
}
