package dynamitedb.ccm.recovery;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.api.MaintenanceGuard;

public class MaintenanceController {
  private TransactionRegistry registry;

  public MaintenanceGuard acquire() {
    return new MaintenanceLease(this);
  }

  public void blockAdmission() {}

  public void resumeAdmission() {}
}
