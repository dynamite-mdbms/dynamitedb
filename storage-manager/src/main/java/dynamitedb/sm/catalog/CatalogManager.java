package dynamitedb.sm.catalog;

import dynamitedb.sm.draft.IndexMetadata;
import dynamitedb.sm.draft.Schema;
import dynamitedb.sm.draft.TableStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SM4 / Audric Yusuf Maynard Simatupang: authoritative catalog and statistics template.
 *
 * <p>Public metadata consumers use StorageCatalogAdapter. The declared maps are placeholders for
 * the diagram's state; loading, persistence and statistics collection remain unimplemented.
 */
public final class CatalogManager {
  private final Map<String, Schema> schemas = new HashMap<>();
  private final Map<String, Long> tableIds = new HashMap<>();
  private final Map<String, TableStatistics> stats = new HashMap<>();

  public List<String> listTables() {
    // TODO(SM4 / SM-08): Enumerate the active catalog as an immutable snapshot.
    throw new UnsupportedOperationException("TODO(SM4): list catalog tables");
  }

  public long getTableId(String table) {
    // TODO(SM4 / SM-08): Resolve a persisted stable ID; never derive it from a name hash/list
    // index.
    throw new UnsupportedOperationException("TODO(SM4): resolve a stable table ID");
  }

  public Schema getSchema(String table) {
    // TODO(SM4 / SM-08): Return an immutable schema in physical row order with
    // type/length/nullability.
    throw new UnsupportedOperationException("TODO(SM4): read a table schema");
  }

  public TableStatistics getStatistics(String table) {
    // TODO(SM4 / SM-09): Agree on collection/refresh with SM1-SM3 and return immutable estimates.
    throw new UnsupportedOperationException("TODO(SM4): read table statistics");
  }

  public List<IndexMetadata> listIndexes(String table) {
    // TODO(SM4/SM5): Return complete, durable index definitions from the same metadata source.
    throw new UnsupportedOperationException("TODO(SM4): list published index metadata");
  }
}
