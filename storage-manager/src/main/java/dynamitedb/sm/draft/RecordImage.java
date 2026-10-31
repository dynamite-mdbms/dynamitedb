package dynamitedb.sm.draft;

/** Temporary contract: Serialized row image; absence differs from an existing SQL NULL. */
public record RecordImage(boolean exists, ImmutableBytes encoded) {}
