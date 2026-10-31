package dynamitedb.ccm.deadlock;

import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;
import java.util.Optional;

public abstract class DeadlockMonitor {
  private LockManager locks;
  private TransactionManager victims;

  public abstract Optional<TransactionCycle> findCycle();

  public abstract TransactionId selectVictim(List<TransactionId> cycle);

  public abstract void resolveDeadlock();
}
