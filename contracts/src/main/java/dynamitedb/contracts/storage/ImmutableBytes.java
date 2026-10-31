package dynamitedb.contracts.storage;

import java.util.Objects;

/** Byte yang selalu disalin saat dibuat dan dibaca. */
public final class ImmutableBytes {
  private final byte[] bytes;

  private ImmutableBytes(byte[] bytes) {
    this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
  }

  public static ImmutableBytes copyOf(byte[] bytes) {
    return new ImmutableBytes(bytes);
  }

  public byte[] copy() {
    return bytes.clone();
  }
}
