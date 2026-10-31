package dynamitedb.contracts.storage;

import java.util.List;

/** Skema tabel dengan urutan kolom sesuai urutan nilai baris. */
public record Schema(List<Column> columns) {
  public Schema {
    columns = List.copyOf(columns);
  }
}
