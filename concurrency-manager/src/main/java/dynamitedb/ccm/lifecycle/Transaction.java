package dynamitedb.ccm.lifecycle;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.util.Objects;

public class Transaction {
  private final TransactionId id;
  private final long timestamp;
  private volatile TransactionStatus status = TransactionStatus.ACTIVE;

  public Transaction(TransactionId id, long timestamp) {
    this.id = Objects.requireNonNull(id, "id");
    if (timestamp <= 0) {
      throw new IllegalArgumentException("Timestamp transaksi harus bernilai positif");
    }
    this.timestamp = timestamp;
  }

  public TransactionId id() {
    return id;
  }

  public long timestamp() {
    return timestamp;
  }

  public synchronized TransactionStatus status() {
    return status;
  }

  public synchronized boolean isActive() {
    return status == TransactionStatus.ACTIVE;
  }

  public synchronized boolean isFinal() {
    return isFinal(status);
  }

  public synchronized void transition(TransactionStatus target) {
    Objects.requireNonNull(target, "target");
    if (!isAllowed(status, target)) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE,
          "Transaksi " + id + " tidak dapat berpindah dari status " + status + " ke " + target);
    }
    status = target;
  }

  public static boolean isFinal(TransactionStatus status) {
    return status == TransactionStatus.COMMITTED || status == TransactionStatus.ABORTED;
  }

  public static boolean isAllowed(TransactionStatus from, TransactionStatus to) {
    return switch (from) {
      case ACTIVE -> to == TransactionStatus.PARTIALLY_COMMITTED || to == TransactionStatus.FAILED;
      case PARTIALLY_COMMITTED ->
          to == TransactionStatus.COMMITTED || to == TransactionStatus.FAILED;
      case FAILED -> to == TransactionStatus.ABORTED;
      case ABORTED, COMMITTED -> false;
    };
  }
}
