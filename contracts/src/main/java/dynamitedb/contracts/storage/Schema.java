package dynamitedb.contracts.storage;

import java.util.List;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record Schema(List<Column> columns) {
  public Schema {
    columns = List.copyOf(columns);
  }
}
