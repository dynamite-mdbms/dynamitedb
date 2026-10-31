package dynamitedb.contracts.api;

import dynamitedb.contracts.storage.IndexKind;

/** Membuat index, metadata baru terbit setelah index lengkap dan durable (SM-10). */
public interface IndexAdmin {
  void createIndex(String name, String table, String column, IndexKind kind);
}
