package dynamitedb.qp.operator;

import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import java.util.List;
import java.util.Map;

final class OperatorRows {

  private OperatorRows() {}

  static Schema qualify(Schema schema, String alias) {
    List<Column> columns =
        schema.columns().stream()
            .map(c -> new Column(alias, c.name(), c.dataType(), c.maxLength(), c.nullable()))
            .toList();
    return new Schema(columns);
  }

  static OperatorRow fromStored(StoredRow stored, Schema qualifiedSchema, String alias) {
    return new OperatorRow(stored.row(), qualifiedSchema, Map.of(alias, stored.rid()));
  }
}
