package dynamitedb.sm.draft;

import java.util.List;

/**
 * Temporary local stand-in for QO3's consumer-owned metadata port.
 *
 * <p>Only StorageCatalogAdapter belongs to SM4. Replace this interface with the agreed shared port;
 * other components must not import this draft package.
 */
public interface CatalogAccess {
  /** Returns an immutable snapshot of table names. */
  List<String> listTables();

  /** Returns the table's stable, persisted identity. */
  long getTableId(String table);

  /** Returns an immutable schema snapshot. */
  Schema getSchema(String table);

  /** Returns an immutable optimizer statistics snapshot. */
  TableStatistics getStatistics(String table);

  /** Returns immutable metadata for complete, durably published indexes. */
  List<IndexMetadata> listIndexes(String table);
}
