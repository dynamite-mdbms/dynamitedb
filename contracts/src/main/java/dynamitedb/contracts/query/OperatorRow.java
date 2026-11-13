package dynamitedb.contracts.query;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record OperatorRow(Row row, Schema schema, Map<String, RecordId> origins) {
  public OperatorRow {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(schema, "schema");
    origins = origins != null ? Map.copyOf(origins) : Map.of();
  }

  public Value getValue(ColumnRef ref) {
    return row.values().get(findColumnIndex(ref));
  }

  public Column getColumn(ColumnRef ref) {
    return schema.columns().get(findColumnIndex(ref));
  }

  // kalau ref ada relation-nya (users.id) cuma cocok ke kolom relation itu,
  // kalau ga ada dan namanya muncul di lebih dari satu relation berarti ambigu
  public int findColumnIndex(ColumnRef ref) {
    List<Column> columns = schema.columns();
    int found = -1;
    for (int i = 0; i < columns.size(); i++) {
      if (matches(columns.get(i), ref)) {
        if (found != -1) {
          throw new DbmsException(ErrorCode.SQL_BIND, "Ambiguous column reference: " + ref);
        }
        found = i;
      }
    }
    if (found == -1) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Column not found: " + ref);
    }
    return found;
  }

  private static boolean matches(Column column, ColumnRef ref) {
    boolean sameName = column.name().equalsIgnoreCase(ref.column());
    boolean sameRelation =
        ref.relation().isEmpty()
            || (column.relation() != null
                && column.relation().equalsIgnoreCase(ref.relation().get()));
    return sameName && sameRelation;
  }
}
