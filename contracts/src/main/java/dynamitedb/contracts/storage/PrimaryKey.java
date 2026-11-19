package dynamitedb.contracts.storage;

import java.util.List;
import java.util.Objects;

/** PRIMARY KEY sebuah table; urutan columns sesuai deklarasi. */
public record PrimaryKey(List<String> columns) {
  public PrimaryKey {
    columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
    if (columns.isEmpty()) {
      throw new IllegalArgumentException("A primary key needs at least one column");
    }
  }
}
