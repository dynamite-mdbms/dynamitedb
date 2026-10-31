package dynamitedb.contracts.api;

import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;

// draf dari QP biar bisa compile, nanti ganti punya CC1
public interface TransactionManager {

  TransactionId begin();

  void commit(TransactionId tx);

  void abort(TransactionId tx);

  TransactionStatus status(TransactionId tx);
}
