package dynamitedb.frm.driver;

import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryStatus;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.testing.TestResult;
import java.util.Objects;

/** Assertion kecil buat driver; sengaja nggak pakai JUnit di runtime utama. */
final class DriverAssertions {
  private DriverAssertions() {}

  static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException("expected=true actual=false: " + message);
  }

  static TestResult result(String scenario, String expected, String actual) {
    boolean passed = Objects.equals(expected, actual);
    return new TestResult(passed, scenario + " expected=" + expected + "; actual=" + actual);
  }

  static void requireSuccess(RecoveryResult result, String operation) {
    if (result.status() != RecoveryStatus.SUCCESS) {
      throw new IllegalStateException(
          operation
              + " expected="
              + RecoveryStatus.SUCCESS
              + "; actual="
              + result.status()
              + ": "
              + result.message());
    }
  }

  static RecordImage image(int value) {
    return new RecordImage(true, ImmutableBytes.copyOf(new byte[] {(byte) value}));
  }
}
