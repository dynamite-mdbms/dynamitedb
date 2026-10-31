package dynamitedb.sm.draft;

/** Temporary contract: FLOAT scalar shape, backed by a Java double. */
public record FloatValue(double value) implements Value {}
