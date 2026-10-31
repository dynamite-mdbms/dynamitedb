package dynamitedb.contracts.storage;

import java.util.Objects;

public record RecordImage(boolean exists, ImmutableBytes encoded) {
  public RecordImage {
    Objects.requireNonNull(encoded, "encoded");
  }

  public static RecordImage absent() {
    return new RecordImage(false, ImmutableBytes.copyOf(new byte[0]));
  }
}
