package dynamitedb.frm.driver;

import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordImage;

/** Assertion kecil buat driver; sengaja nggak pakai JUnit di runtime utama. */
final class DriverAssertions {
  private DriverAssertions() {}

  static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  static void requireSuccess(RecoveryResult result, String operation) {
    if (result.status() != RecoveryStatus.SUCCESS) {
      throw new IllegalStateException(operation + " gagal: " + result.message());
    }
  }

  static RecordImage image(int value) {
    return new RecordImage(true, ImmutableBytes.copyOf(new byte[] {(byte) value}));
  }
}
