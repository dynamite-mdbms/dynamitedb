package dynamitedb.sm.draft;

/** Temporary contract: Ordered WAL position; durability is supplied by FRM. */
public record LogSequenceNumber(long value) {}
