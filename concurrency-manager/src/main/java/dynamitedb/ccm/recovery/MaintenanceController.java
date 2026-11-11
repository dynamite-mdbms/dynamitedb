package dynamitedb.ccm.recovery;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.api.MaintenanceGuard;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class MaintenanceController {
  private final TransactionRegistry registry;
  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition changed = lock.newCondition();
  private boolean blocked;

  public MaintenanceController(TransactionRegistry registry) {
    this.registry = registry;
  }

  public MaintenanceGuard acquire() {
    lock.lock();
    try {
      while (blocked) changed.awaitUninterruptibly();
      blocked = true;
      while (registry.activeCount() != 0) changed.awaitUninterruptibly();
      return new MaintenanceLease(this);
    } finally {
      lock.unlock();
    }
  }

  public <T> T beginTransaction(Supplier<T> create) {
    lock.lock();
    try {
      while (blocked) changed.awaitUninterruptibly();
      return create.get();
    } finally {
      lock.unlock();
    }
  }

  public void transactionFinished() {
    lock.lock();
    try {
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  void resumeAdmission() {
    lock.lock();
    try {
      blocked = false;
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
