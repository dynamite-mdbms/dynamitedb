package dynamitedb.ccm.recovery;

import dynamitedb.contracts.api.MaintenanceGuard;
import java.util.Objects;

public class MaintenanceLease implements MaintenanceGuard {
  private final MaintenanceController controller;
  private boolean closed;

  public MaintenanceLease(MaintenanceController controller) {
    this.controller = Objects.requireNonNull(controller, "controller");
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      controller.releaseLease();
      closed = true;
    }
  }
}
