package dynamitedb.frm;

import dynamitedb.contracts.HistoricalRecoveryService;
import dynamitedb.contracts.RecoveryBoundary;
import dynamitedb.contracts.RecoveryResult;
import dynamitedb.contracts.RecoveryTarget;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;

/** Configurable Milestone 1 mock; no replay or publication is performed. */
public final class HistoricalRecoveryManager implements HistoricalRecoveryService {
  private final RecoveryResult result;
  private final RecoveryBoundary boundary;
  private final MockRecoveryContext context;

  public HistoricalRecoveryManager(RecoveryResult result, RecoveryBoundary boundary) {
    this(result, boundary, new MockRecoveryContext());
  }

  public HistoricalRecoveryManager(MockRecoveryContext context) {
    this(context.result(), context.boundary(), context);
  }

  public HistoricalRecoveryManager(
      RecoveryResult result, RecoveryBoundary boundary, MockRecoveryContext context) {
    this.result = Objects.requireNonNull(result, "result");
    this.boundary = Objects.requireNonNull(boundary, "boundary");
    this.context = Objects.requireNonNull(context, "context");
  }

  @Override
  public RecoveryResult recoverTo(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoricalRecoveryManager.recoverTo");
    return result;
  }

  public RecoveryBoundary resolveBoundary(RecoveryTarget target) {
    Objects.requireNonNull(target, "target");
    context.invoked("HistoricalRecoveryManager.resolveBoundary");
    return boundary;
  }
}
