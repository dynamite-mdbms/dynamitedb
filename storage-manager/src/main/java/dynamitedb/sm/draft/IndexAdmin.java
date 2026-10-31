package dynamitedb.sm.draft;

/** Temporary SM5 admin port; publish metadata only after a complete durable index exists. */
public interface IndexAdmin {
  void createIndex(String index, String table, String column, IndexKind kind);
}
