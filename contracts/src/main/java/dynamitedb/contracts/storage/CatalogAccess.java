package dynamitedb.contracts.storage;

import java.util.List;

// draf dari QP biar bisa compile, nanti ganti punya QO3
public interface CatalogAccess {

  List<String> listTables();

  long getTableId(String table);

  Schema getSchema(String table);

  TableStatistics getStatistics(String table);

  List<IndexMetadata> listIndexes(String table);
}
