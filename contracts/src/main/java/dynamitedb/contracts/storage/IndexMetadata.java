package dynamitedb.contracts.storage;

// draf dari QP biar bisa compile, nanti ganti punya SM
public record IndexMetadata(String name, String table, String column, IndexKind kind) {}
