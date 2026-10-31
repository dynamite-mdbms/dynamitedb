package dynamitedb.contracts;

/** Startup recovery port. Failed recovery must keep client admission closed. */
public interface CrashRecovery {
  RecoveryResult recover();
}
