package dynamitedb.frm;

import dynamitedb.contracts.RecoveryBoundary;
import dynamitedb.contracts.RecoveryResult;
import dynamitedb.contracts.RecoveryTarget;
import java.util.Objects;

/** Configurable milestone 1 mock for historical recovery. */
public final class HistoricalRecoveryManager {
  private final RecoveryResult result;
  private final RecoveryBoundary boundary;

  public HistoricalRecoveryManager(RecoveryResult result, RecoveryBoundary boundary) {
    this.result = Objects.requireNonNull(result, "result");
    this.boundary = Objects.requireNonNull(boundary, "boundary");
  }

  public RecoveryResult recoverTo(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    return result;
  }

  public RecoveryBoundary resolveBoundary(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    return boundary;
  }
}
