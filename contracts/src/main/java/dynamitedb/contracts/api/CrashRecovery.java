package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.RecoveryResult;

/** Startup recovery port. Failed recovery must keep client admission closed. */
public interface CrashRecovery {
  RecoveryResult recover();
}
