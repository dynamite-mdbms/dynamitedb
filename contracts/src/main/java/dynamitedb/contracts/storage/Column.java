package dynamitedb.contracts.storage;

import java.util.Objects;

public record Column(
    String relation, String name, DataType dataType, int maxLength, boolean nullable) {

  public Column {
    Objects.requireNonNull(name, "name");
  }
}
