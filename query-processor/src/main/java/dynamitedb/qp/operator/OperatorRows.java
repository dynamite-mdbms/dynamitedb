package dynamitedb.qp.operator;

import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import java.util.Map;

final class OperatorRows {

  private OperatorRows() {}

  static Schema qualify(Schema schema, String alias) {
    return schema;
  }

  static OperatorRow fromStored(StoredRow stored, Schema qualifiedSchema, String alias) {
    return new OperatorRow(stored.row(), qualifiedSchema, Map.of(alias, stored.rid()));
  }
}
