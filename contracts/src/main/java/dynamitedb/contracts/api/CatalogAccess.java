package dynamitedb.contracts.api;

import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.List;
import java.util.Optional;

// draf dari QP biar bisa compile, nanti ganti punya QO3 (implementasi di SM4)
public interface CatalogAccess {

  List<String> listTables();

  long getTableId(String table);

  Schema getSchema(String table);

  TableStatistics getStatistics(String table);

  List<IndexMetadata> listIndexes(String table);

  /** PRIMARY KEY table; default kosong supaya fake lama tetap compile. */
  default Optional<PrimaryKey> getPrimaryKey(String table) {
    return Optional.empty();
  }

  /** FOREIGN KEY milik table (outgoing); dipakai QP saat INSERT/UPDATE child. */
  default List<ForeignKey> listForeignKeys(String table) {
    return List.of();
  }

  /** FOREIGN KEY dari table lain ke table ini (incoming); dipakai QP saat DELETE parent. */
  default List<ForeignKey> listReferencingForeignKeys(String table) {
    return List.of();
  }
}
