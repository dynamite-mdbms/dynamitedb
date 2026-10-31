package dynamitedb.contracts;

import java.util.Arrays;
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

  @Override
  public boolean equals(Object other) {
    return other instanceof ImmutableBytes value && Arrays.equals(bytes, value.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }
}
