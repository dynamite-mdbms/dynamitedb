package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import java.util.Map;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record OperatorRow(Row row, Schema schema, Map<String, RecordId> origins) {
  public OperatorRow {
    origins = Map.copyOf(origins);
  }
}
