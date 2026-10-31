package dynamitedb.contracts.storage;

/** Predikat pencarian index, HASH hanya menerima EQ. */
public record IndexPredicate(ComparisonOp operator, Value value) {}
