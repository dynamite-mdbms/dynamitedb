package dynamitedb.contracts.storage;

import java.util.List;

public record Row(List<Value> values) {

  public Row {
    values = List.copyOf(values);
  }
}
