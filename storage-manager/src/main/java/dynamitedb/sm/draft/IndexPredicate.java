package dynamitedb.sm.draft;

/** Temporary contract: Typed index comparison; HASH supports EQ only. */
public record IndexPredicate(ComparisonOp operator, Value value) {}
