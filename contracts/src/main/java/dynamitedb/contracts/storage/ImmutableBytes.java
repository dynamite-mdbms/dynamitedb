package dynamitedb.contracts.storage;

// draf dari QP biar bisa compile, nanti ganti punya SM
public class ImmutableBytes {

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
}
