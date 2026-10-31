package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.RecoveryResult;
import dynamitedb.contracts.recovery.RecoveryTarget;

/** Historical recovery port selecting a committed prefix through a staged generation. */
public interface HistoricalRecoveryService {
  RecoveryResult recoverTo(RecoveryTarget target);
}
