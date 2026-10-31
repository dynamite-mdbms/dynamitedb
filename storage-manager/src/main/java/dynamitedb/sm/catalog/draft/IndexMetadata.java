package dynamitedb.sm.catalog.draft;

/** Temporary immutable index definition; replace with the agreed shared IndexMetadata contract. */
public record IndexMetadata(String name, String table, String column, IndexKind kind) {}
