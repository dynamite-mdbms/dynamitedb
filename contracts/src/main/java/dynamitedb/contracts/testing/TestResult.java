package dynamitedb.contracts.testing;

import java.util.Objects;

public record TestResult(boolean passed, String message) {
  public TestResult {
    Objects.requireNonNull(message, "message");
  }
}
