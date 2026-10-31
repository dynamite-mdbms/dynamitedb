package dynamitedb.sm.catalog;

import dynamitedb.sm.catalog.draft.IndexMetadata;
import dynamitedb.sm.catalog.draft.Schema;
import dynamitedb.sm.catalog.draft.TableStatistics;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * SM4 catalog and statistics template owned by Audric Yusuf Maynard Simatupang.
 *
 * <p>This is the storage manager's authoritative metadata source. Public metadata consumers use
 * {@link StorageCatalogAdapter}; they must not depend directly on this class.
 */
public final class CatalogManager {
  private final Path storageRoot;

  public CatalogManager(Path storageRoot) {
    this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot");
    // TODO(SM4): Define the persisted catalog format and how the selected generation is opened.
  }

  /** Returns the configured storage root; construction does not read or create database files. */
  public Path storageRoot() {
    return storageRoot;
  }

  /** Returns an immutable snapshot of table names in the active catalog. */
  public List<String> listTables() {
    // TODO(SM4 / SM-08): Enumerate the authoritative catalog without exposing mutable collections.
    throw new UnsupportedOperationException("TODO(SM4): list catalog tables");
  }

  /**
   * Resolves a persisted, stable table identity; never derive this from a name hash or list index.
   */
  public long getTableId(String table) {
    // TODO(SM4 / SM-08): Resolve the stable ID and report unknown tables with the shared error
    // type.
    throw new UnsupportedOperationException("TODO(SM4): resolve a stable table ID");
  }

  /** Returns an immutable schema snapshot whose column order matches the physical row format. */
  public Schema getSchema(String table) {
    // TODO(SM4 / SM-08): Resolve the table schema, including types, lengths and nullability.
    throw new UnsupportedOperationException("TODO(SM4): read a table schema");
  }

  /** Returns row/block counts, average row size and distinct-value estimates for the optimizer. */
  public TableStatistics getStatistics(String table) {
    // TODO(SM4 / SM-09): Define how statistics are collected, refreshed and persisted with SM1/SM2.
    // Return an immutable snapshot rather than a mutable statistics accumulator.
    throw new UnsupportedOperationException("TODO(SM4): read table statistics");
  }

  /** Returns immutable metadata for complete, durable indexes published for this table. */
  public List<IndexMetadata> listIndexes(String table) {
    // TODO(SM4 / SM-08): Read published definitions from the same catalog as schemas/statistics.
    // Keep index construction and HASH/B_PLUS_TREE algorithms with SM5.
    throw new UnsupportedOperationException("TODO(SM4): list published index metadata");
  }

  /**
   * Proposed internal entry point for initial table registration; agree on its signature with SM1.
   */
  public long registerTable(String table, Schema schema) {
    // TODO(SM4): Validate the definition, allocate a stable ID and durably register the metadata.
    // Coordinate physical table creation with SM1; do not announce a partially initialized table.
    throw new UnsupportedOperationException("TODO(SM4): register table metadata");
  }

  /** Proposed internal entry point for replacing a table's statistics snapshot. */
  public void updateStatistics(String table, TableStatistics statistics) {
    // TODO(SM4 / SM-09): Validate and durably replace estimates for an existing table.
    throw new UnsupportedOperationException("TODO(SM4): update table statistics");
  }

  /**
   * Proposed internal publication hook; SM5 calls it only after an index is complete and durable.
   */
  public void registerIndex(IndexMetadata index) {
    // TODO(SM4): Agree on atomic publication with SM5 and validate the table/column/name/kind.
    throw new UnsupportedOperationException("TODO(SM4): publish index metadata");
  }
}
