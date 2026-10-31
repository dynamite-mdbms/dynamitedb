package dynamitedb.contracts.storage;

import java.util.List;

/** Isi baris dengan urutan nilai sesuai Schema. */
public record Row(List<Value> values) {
  public Row {
    values = List.copyOf(values);
  }
}
