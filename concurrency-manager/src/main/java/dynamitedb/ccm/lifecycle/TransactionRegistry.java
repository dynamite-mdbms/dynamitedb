package dynamitedb.ccm.lifecycle;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.TransactionId;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionRegistry {
  static final int RETAINED_TRANSACTION_LIMIT = 1024;

  private final Map<TransactionId, Transaction> transactions = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong(1);
  private final AtomicLong nextTimestamp = new AtomicLong(1);
  private final Set<TransactionId> active = new TreeSet<>();
  private final Queue<TransactionId> finalizedOrder = new ArrayDeque<>();
  private int admissionHolds;

  public synchronized Transaction create() {
    while (admissionHolds > 0) {
      try {
        wait();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new DbmsException(
            ErrorCode.RECOVERY_FAILURE, "Menunggu maintenance selesai terinterupsi", interrupted);
      }
    }
    Transaction tx =
        new Transaction(
            new TransactionId(nextId.getAndIncrement()), nextTimestamp.getAndIncrement());
    transactions.put(tx.id(), tx);
    active.add(tx.id());
    return tx;
  }

  public Transaction get(TransactionId tx) {
    return find(tx)
        .orElseThrow(
            () -> new DbmsException(ErrorCode.INVALID_STATE, "Transaksi " + tx + " tidak dikenal"));
  }

  public Optional<Transaction> find(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    return Optional.ofNullable(transactions.get(tx));
  }

  public synchronized int activeCount() {
    return active.size();
  }

  public synchronized List<TransactionId> activeTransactions() {
    return List.copyOf(active);
  }

  public void markFinished(Transaction tx) {
    Objects.requireNonNull(tx, "tx");
    if (!tx.isFinal()) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Transaksi " + tx.id() + " belum mencapai status final");
    }
    synchronized (this) {
      if (active.remove(tx.id()) && active.isEmpty()) {
        notifyAll();
      }
      finalizedOrder.add(tx.id());
      while (transactions.size() > RETAINED_TRANSACTION_LIMIT) {
        TransactionId oldest = finalizedOrder.poll();
        if (oldest == null) {
          break;
        }
        transactions.remove(oldest);
      }
    }
  }

  public synchronized int size() {
    return transactions.size();
  }

  public synchronized void holdAdmission() {
    admissionHolds++;
  }

  public synchronized void releaseAdmission() {
    if (admissionHolds == 0) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Tidak ada penahanan transaksi baru yang dapat dilepas");
    }
    admissionHolds--;
    notifyAll();
  }

  public synchronized boolean isAdmissionHeld() {
    return admissionHolds > 0;
  }

  public synchronized boolean awaitNoActive(Duration timeout) throws InterruptedException {
    Objects.requireNonNull(timeout, "timeout");
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!active.isEmpty()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return false;
      }
      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
    return true;
  }

  public synchronized boolean runIfIdle(Runnable action) {
    Objects.requireNonNull(action, "action");
    if (!active.isEmpty()) {
      return false;
    }
    action.run();
    return true;
  }

  public synchronized void restoreHighWater(RecoveryHighWater highWater) {
    Objects.requireNonNull(highWater, "highWater");
    if (highWater.maxTx().value() < 0
        || highWater.maxTx().value() == Long.MAX_VALUE
        || highWater.maxTimestamp() < 0
        || highWater.maxTimestamp() == Long.MAX_VALUE) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "High-water transaksi tidak memiliki ID/timestamp penerus");
    }
    transactions.clear();
    active.clear();
    finalizedOrder.clear();
    notifyAll();
    nextId.set(Math.max(nextId.get(), highWater.maxTx().value() + 1));
    nextTimestamp.set(Math.max(nextTimestamp.get(), highWater.maxTimestamp() + 1));
  }
}
