package dynamitedb.contracts.transaction;

// draf dari QP biar bisa compile, nanti ganti punya CCM
public enum TransactionStatus {
  ACTIVE,
  PARTIALLY_COMMITTED,
  FAILED,
  ABORTED,
  COMMITTED
}
