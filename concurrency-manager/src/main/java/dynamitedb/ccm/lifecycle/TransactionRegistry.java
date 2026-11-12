package dynamitedb.ccm.lifecycle;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionRegistry {
  private final Map<TransactionId, Transaction> transactions = new ConcurrentHashMap<>();
  private final NavigableMap<Long, FinalRange> finalized = new TreeMap<>();
  private final AtomicLong nextId = new AtomicLong(1);
  private final AtomicLong nextTimestamp = new AtomicLong(1);
  private final Set<TransactionId> active = new TreeSet<>();
  private int admissionHolds;

  public synchronized Transaction create() {
    if (admissionHolds > 0) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Transaksi baru sedang ditahan karena maintenance berjalan");
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

  public synchronized TransactionStatus status(TransactionId tx) {
    Objects.requireNonNull(tx, "tx");
    Transaction current = transactions.get(tx);
    if (current != null) {
      return current.status();
    }
    var entry = finalized.floorEntry(tx.value());
    if (entry != null && tx.value() <= entry.getValue().end) {
      return entry.getValue().status;
    }
    throw new DbmsException(ErrorCode.INVALID_STATE, "Transaksi " + tx + " tidak dikenal");
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
      if (!transactions.remove(tx.id(), tx)) {
        throw new DbmsException(ErrorCode.INVALID_STATE, "Transaksi " + tx.id() + " tidak aktif");
      }
      long id = tx.id().value();
      var before = finalized.floorEntry(id);
      var after = finalized.ceilingEntry(id);
      long start = id;
      long end = id;
      if (before != null
          && before.getValue().status == tx.status()
          && before.getValue().end + 1 == id) {
        start = before.getKey();
        finalized.remove(before.getKey());
      }
      if (after != null && after.getValue().status == tx.status() && after.getKey() == id + 1) {
        end = after.getValue().end;
        finalized.remove(after.getKey());
      }
      finalized.put(start, new FinalRange(end, tx.status()));
      if (active.remove(tx.id()) && active.isEmpty()) {
        notifyAll();
      }
    }
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
    finalized.clear();
    active.clear();
    notifyAll();
    nextId.set(Math.max(nextId.get(), highWater.maxTx().value() + 1));
    nextTimestamp.set(Math.max(nextTimestamp.get(), highWater.maxTimestamp() + 1));
  }

  private record FinalRange(long end, TransactionStatus status) {}
}
