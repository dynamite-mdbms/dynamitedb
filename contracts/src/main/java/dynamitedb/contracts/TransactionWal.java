package dynamitedb.contracts;

/** Transaction lifecycle WAL port. Real commits require durable COMMIT before returning. */
public interface TransactionWal {
  void begin(TransactionId tx, long timestamp);

  LogSequenceNumber appendChange(ChangeRecord change);

  void commitDurably(TransactionId tx);
}
