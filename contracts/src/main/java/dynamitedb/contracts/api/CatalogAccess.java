package dynamitedb.contracts.api;

import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.List;

public interface CatalogAccess {

  List<String> listTables();

  long getTableId(String table);

  Schema getSchema(String table);

  TableStatistics getStatistics(String table);

  List<IndexMetadata> listIndexes(String table);
}
