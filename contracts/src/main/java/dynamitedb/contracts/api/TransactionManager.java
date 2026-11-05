package dynamitedb.contracts.api;

import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;

public interface TransactionManager {
  TransactionId begin();

  void commit(TransactionId tx);

  void abort(TransactionId tx);

  TransactionStatus status(TransactionId tx);
}
