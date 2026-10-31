package dynamitedb.sm.catalog;

import dynamitedb.sm.catalog.draft.CatalogAccess;
import dynamitedb.sm.catalog.draft.IndexMetadata;
import dynamitedb.sm.catalog.draft.Schema;
import dynamitedb.sm.catalog.draft.TableStatistics;
import java.util.List;
import java.util.Objects;

/**
 * SM4 provider of the optimizer's read-only metadata port.
 *
 * <p>The port is temporarily local because the shared contracts module is empty. Replace draft
 * imports with the agreed shared contracts when available. All reads use one catalog instance.
 */
public final class StorageCatalogAdapter implements CatalogAccess {
  private final CatalogManager catalog;

  public StorageCatalogAdapter(CatalogManager catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public List<String> listTables() {
    return catalog.listTables();
  }

  @Override
  public long getTableId(String table) {
    return catalog.getTableId(table);
  }

  @Override
  public Schema getSchema(String table) {
    return catalog.getSchema(table);
  }

  @Override
  public TableStatistics getStatistics(String table) {
    return catalog.getStatistics(table);
  }

  @Override
  public List<IndexMetadata> listIndexes(String table) {
    return catalog.listIndexes(table);
  }
}
