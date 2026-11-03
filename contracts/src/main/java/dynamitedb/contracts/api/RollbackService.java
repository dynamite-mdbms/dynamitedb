package dynamitedb.contracts.api;

import dynamitedb.contracts.transaction.TransactionId;

public interface RollbackService {
  void rollbackCompletely(TransactionId tx);
}
