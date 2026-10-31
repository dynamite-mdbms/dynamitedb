package dynamitedb.sm.draft;

import java.util.List;

/** Temporary immutable row shape; values follow Schema.columns order. */
public record Row(List<Value> values) {
  public Row {
    values = List.copyOf(values);
  }
}
