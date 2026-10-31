package dynamitedb.contracts;

import java.util.Objects;

public final class ImmutableBytes {
  private final byte[] bytes;

  private ImmutableBytes(byte[] data) {
    this.bytes = Objects.requireNonNull(data, "data").clone();
  }

  public static ImmutableBytes copyOf(byte[] data) {
    return new ImmutableBytes(data);
  }

  public byte[] copy() {
    return bytes.clone();
  }
}
