package dynamitedb.contracts.storage;

import java.util.List;
import java.util.Objects;

/** FOREIGN KEY (columns) dari table ke PRIMARY KEY referencedTable (referencedColumns). */
public record ForeignKey(
    String table, List<String> columns, String referencedTable, List<String> referencedColumns) {
  public ForeignKey {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(referencedTable, "referencedTable");
    columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
    referencedColumns = List.copyOf(Objects.requireNonNull(referencedColumns, "referencedColumns"));
    if (columns.isEmpty() || columns.size() != referencedColumns.size()) {
      throw new IllegalArgumentException("A foreign key needs matching, non-empty column lists");
    }
  }
}
