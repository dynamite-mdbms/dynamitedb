package dynamitedb.contracts.storage;

import java.util.Arrays;

public final class ImmutableBytes {
  private final byte[] bytes;

  private ImmutableBytes(byte[] bytes) {
    this.bytes = bytes;
  }

  public static ImmutableBytes copyOf(byte[] bytes) {
    return new ImmutableBytes(bytes.clone());
  }

  public byte[] copy() {
    return bytes.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ImmutableBytes that && Arrays.equals(bytes, that.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "ImmutableBytes[" + bytes.length + " bytes]";
  }
}
