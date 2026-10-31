package dynamitedb.sm.catalog.draft;

import java.util.List;

/** Temporary immutable schema snapshot; column order is the row value order. */
public record Schema(List<Column> columns) {
  public Schema {
    columns = List.copyOf(columns);
  }
}
