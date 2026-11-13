package dynamitedb.ccm.deadlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class DeadlockMonitorTest {
  private final LockManager locks = new LockManager();
  private final RecordingTransactions transactions = new RecordingTransactions();
  private final DeadlockMonitor monitor = new DeadlockMonitor(locks, transactions);

  @Test
  void doesNotReportAWaitWithoutCycle() {
    TransactionId owner = tx(1);
    TransactionId waiter = tx(2);
    locks.acquire(request(owner, 1));
    locks.acquire(request(waiter, 1));

    assertTrue(monitor.findCycle().isEmpty());
    monitor.resolveDeadlock();
    assertTrue(transactions.aborted.isEmpty());
  }

  @Test
  void findsTwoTransactionCycleAndSelectsNewestVictim() {
    TransactionId first = tx(1);
    TransactionId second = tx(2);
    locks.acquire(request(first, 1));
    locks.acquire(request(second, 2));
    locks.acquire(request(first, 2));
    locks.acquire(request(second, 1));

    TransactionCycle cycle = monitor.findCycle().orElseThrow();

    assertEquals(List.of(first, second), cycle.txIds());
    assertEquals(second, monitor.selectVictim(cycle.txIds()));
  }

  @Test
  void detectsDeadlockFromTwoSharedLockUpgrades() {
    TransactionId first = tx(1);
    TransactionId second = tx(2);
    locks.acquire(new AccessRequest(first, ResourceId.table(1), AccessMode.READ));
    locks.acquire(new AccessRequest(second, ResourceId.table(1), AccessMode.READ));
    locks.acquire(request(first, 1));
    locks.acquire(request(second, 1));

    assertEquals(List.of(first, second), monitor.findCycle().orElseThrow().txIds());
  }

  @Test
  void selectsOneVictimFromThreeTransactionCycle() {
    TransactionId first = tx(1);
    TransactionId second = tx(2);
    TransactionId third = tx(3);
    locks.acquire(request(first, 1));
    locks.acquire(request(second, 2));
    locks.acquire(request(third, 3));
    locks.acquire(request(first, 2));
    locks.acquire(request(second, 3));
    locks.acquire(request(third, 1));

    monitor.resolveDeadlock();

    assertEquals(List.of(third), transactions.aborted);
    assertTrue(monitor.findCycle().isEmpty());
  }

  @Test
  void rejectsEmptyVictimSet() {
    assertThrows(IllegalArgumentException.class, () -> monitor.selectVictim(List.of()));
  }

  private static TransactionId tx(long id) {
    return new TransactionId(id);
  }

  private static AccessRequest request(TransactionId tx, long tableId) {
    return new AccessRequest(tx, ResourceId.table(tableId), AccessMode.WRITE);
  }

  private final class RecordingTransactions implements TransactionManager {
    private final Map<TransactionId, TransactionStatus> statuses = new ConcurrentHashMap<>();
    private final List<TransactionId> aborted = new ArrayList<>();

    @Override
    public TransactionId begin() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void commit(TransactionId tx) {
      statuses.put(tx, TransactionStatus.COMMITTED);
    }

    @Override
    public void abort(TransactionId tx) {
      aborted.add(tx);
      statuses.put(tx, TransactionStatus.ABORTED);
      locks.releaseAll(tx);
    }

    @Override
    public TransactionStatus status(TransactionId tx) {
      return statuses.getOrDefault(tx, TransactionStatus.ACTIVE);
    }
  }
}
