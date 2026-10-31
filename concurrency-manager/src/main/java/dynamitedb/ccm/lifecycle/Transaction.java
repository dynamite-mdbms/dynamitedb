package dynamitedb.ccm.lifecycle;

import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;

public class Transaction {
  private final TransactionId id;
  private final long timestamp;
  private TransactionStatus status = TransactionStatus.ACTIVE;

  public Transaction(TransactionId id, long timestamp) {
    this.id = id;
    this.timestamp = timestamp;
  }

  public TransactionId id() {
    return id;
  }

  public long timestamp() {
    return timestamp;
  }

  public TransactionStatus status() {
    return status;
  }

  public void transition(TransactionStatus target) {
    status = target;
  }
}
