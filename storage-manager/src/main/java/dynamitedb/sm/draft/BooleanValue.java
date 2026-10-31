package dynamitedb.sm.draft;

/** Temporary contract: Expression boolean; not a persisted SQL column type. */
public record BooleanValue(boolean value) implements Value {}
