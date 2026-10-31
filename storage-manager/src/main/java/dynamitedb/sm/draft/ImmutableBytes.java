package dynamitedb.sm.draft;

import java.util.Objects;

/** Temporary binary value holder; defensive copies prevent aliasing of row/WAL images. */
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
