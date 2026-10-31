package dynamitedb.contracts.storage;

import java.util.Objects;

public record RecordImage(boolean exists, ImmutableBytes encoded) {
  public RecordImage {
    Objects.requireNonNull(encoded, "encoded");
    if (!exists && encoded.copy().length != 0) {
      throw new IllegalArgumentException("An absent record image must be empty");
    }
  }

  public static RecordImage absent() {
    return new RecordImage(false, ImmutableBytes.copyOf(new byte[0]));
  }
}
