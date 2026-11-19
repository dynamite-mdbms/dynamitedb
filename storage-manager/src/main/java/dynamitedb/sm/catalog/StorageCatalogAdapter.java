package dynamitedb.sm.catalog;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Implementasi CatalogAccess milik QO di atas CatalogManager (SM-08, SM-09). */
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

  @Override
  public Optional<PrimaryKey> getPrimaryKey(String table) {
    return catalog.getPrimaryKey(table);
  }

  @Override
  public List<ForeignKey> listForeignKeys(String table) {
    return catalog.listForeignKeys(table);
  }

  @Override
  public List<ForeignKey> listReferencingForeignKeys(String table) {
    return catalog.listReferencingForeignKeys(table);
  }
}
