package dynamitedb.contracts;

/** Historical recovery port selecting a committed prefix through a staged generation. */
public interface HistoricalRecoveryService {
  RecoveryResult recoverTo(RecoveryTarget target);
}
