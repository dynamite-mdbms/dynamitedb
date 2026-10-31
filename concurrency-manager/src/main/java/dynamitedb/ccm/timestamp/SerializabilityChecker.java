package dynamitedb.ccm.timestamp;

public abstract class SerializabilityChecker {
  public abstract VerificationResult verify(Schedule schedule);
}
