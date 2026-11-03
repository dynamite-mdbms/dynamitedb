package dynamitedb.contracts.api;

import dynamitedb.contracts.storage.IndexKind;

// draf dari QP biar bisa compile, nanti ganti punya SM5
public interface IndexAdmin {

  void createIndex(String name, String table, String column, IndexKind kind);
}
