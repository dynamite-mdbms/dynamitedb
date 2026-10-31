package dynamitedb.contracts.storage;

import java.util.List;

public record Schema(List<Column> columns) {

  public Schema {
    columns = List.copyOf(columns);
  }
}
