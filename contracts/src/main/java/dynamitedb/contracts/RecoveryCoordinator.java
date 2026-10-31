package dynamitedb.contracts;

/** CCM-owned recovery coordination port consumed by FRM; contains no CCM implementation. */
public interface RecoveryCoordinator {
  MaintenanceGuard acquireMaintenance();

  long transactionTimestamp(TransactionId tx);

  void initializeRecoveredState(RecoveryHighWater highWater);
}
