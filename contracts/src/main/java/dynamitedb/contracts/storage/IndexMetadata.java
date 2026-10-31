package dynamitedb.contracts.storage;

/** Definisi index yang sudah terbit di katalog. */
public record IndexMetadata(String name, String table, String column, IndexKind kind) {}
