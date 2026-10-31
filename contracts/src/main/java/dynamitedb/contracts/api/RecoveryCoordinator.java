package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.TransactionId;

public interface RecoveryCoordinator {
  MaintenanceGuard acquireMaintenance();

  long transactionTimestamp(TransactionId tx);

  void initializeRecoveredState(RecoveryHighWater highWater);
}
