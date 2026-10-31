package dynamitedb.sm.draft;

/** Explicit SQL NULL; Java null must not represent a stored SQL value. */
public enum NullValue implements Value {
  INSTANCE
}
