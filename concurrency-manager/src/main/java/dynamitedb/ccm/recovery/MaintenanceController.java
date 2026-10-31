package dynamitedb.ccm.recovery;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.api.MaintenanceGuard;

public class MaintenanceController {
  private final TransactionRegistry registry;

  public MaintenanceController(TransactionRegistry registry) {
    this.registry = registry;
  }

  public MaintenanceGuard acquire() {
    return new MaintenanceLease(this);
  }

  public void blockAdmission() {}

  public void resumeAdmission() {}
}
