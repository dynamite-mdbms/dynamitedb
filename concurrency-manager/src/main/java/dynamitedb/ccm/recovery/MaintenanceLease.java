package dynamitedb.ccm.recovery;

import dynamitedb.contracts.api.MaintenanceGuard;

public class MaintenanceLease implements MaintenanceGuard {
  private final MaintenanceController controller;
  private boolean closed;

  public MaintenanceLease(MaintenanceController controller) {
    this.controller = controller;
  }

  @Override
  public void close() {
    closed = true;
  }
}
