package dynamitedb.contracts;

import java.util.Objects;

public record RecordImage(boolean exists, ImmutableBytes encoded) {
  public RecordImage {
    Objects.requireNonNull(encoded, "encoded");
    if (!exists && encoded.copy().length != 0) {
      throw new IllegalArgumentException("An absent record image must be empty");
    }
  }
}
