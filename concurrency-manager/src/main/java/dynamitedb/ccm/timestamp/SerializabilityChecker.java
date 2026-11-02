package dynamitedb.ccm.timestamp;

import java.util.List;

public class SerializabilityChecker {
  public VerificationResult verify(Schedule schedule) {
    return new VerificationResult(false, List.of());
  }
}
