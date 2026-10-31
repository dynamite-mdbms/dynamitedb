package dynamitedb.ccm.deadlock;

import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;
import java.util.Optional;

public class DeadlockMonitor {
  private final LockManager locks;
  private final TransactionManager victims;

  public DeadlockMonitor(LockManager locks, TransactionManager victims) {
    this.locks = locks;
    this.victims = victims;
  }

  public Optional<TransactionCycle> findCycle() {
    return Optional.empty();
  }

  public TransactionId selectVictim(List<TransactionId> cycle) {
    return null;
  }

  public void resolveDeadlock() {}
}
