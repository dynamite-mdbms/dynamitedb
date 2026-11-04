package dynamitedb.ccm.lifecycle;

import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionRegistry {
  private final Map<TransactionId, Transaction> transactions = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong(1);
  private final AtomicLong nextTimestamp = new AtomicLong(1);

  public Transaction create() {
    Transaction tx =
        new Transaction(
            new TransactionId(nextId.getAndIncrement()), nextTimestamp.getAndIncrement());
    transactions.put(tx.id(), tx);
    return tx;
  }

  public Transaction get(TransactionId tx) {
    return transactions.get(tx);
  }

  public int activeCount() {
    return 0;
  }

  public void restoreHighWater(RecoveryHighWater highWater) {
    nextId.set(highWater.maxTx().value() + 1);
    nextTimestamp.set(highWater.maxTimestamp() + 1);
  }
}
