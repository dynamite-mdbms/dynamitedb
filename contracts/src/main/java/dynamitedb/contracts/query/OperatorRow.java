package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import java.util.Map;
import java.util.Objects;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record OperatorRow(Row row, Schema schema, Map<String, RecordId> origins) {
  public OperatorRow {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(schema, "schema");
    origins = origins != null ? Map.copyOf(origins) : Map.of();
  }

  /** Stub method untuk mengambil nilai berdasarkan ColumnRef. */
  public Value getValue(ColumnRef colRef) {
    int index = findColumnIndex(colRef);
    if (index == -1 || index >= row.values().size()) {
      return null;
    }
    return row.values().get(index);
  }

  /** Stub method untuk mengambil definisi kolom berdasarkan ColumnRef. */
  public Column getColumn(ColumnRef colRef) {
    int index = findColumnIndex(colRef);
    if (index == -1 || index >= schema.columns().size()) {
      return null;
    }
    return schema.columns().get(index);
  }

  /** Helper internal untuk mencocokkan ColumnRef dengan indeks kolom pada Schema. */
  private int findColumnIndex(ColumnRef colRef) {
    for (int i = 0; i < schema.columns().size(); i++) {
      Column col = schema.columns().get(i);
      if (col.name().equalsIgnoreCase(colRef.columnName())) {
        return i;
      }
    }
    return -1;
  }
}
