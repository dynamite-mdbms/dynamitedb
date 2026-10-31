package dynamitedb.contracts.transaction;

public enum TransactionStatus {
  ACTIVE,
  PARTIALLY_COMMITTED,
  FAILED,
  ABORTED,
  COMMITTED;

  public boolean isFinal() {
    return this == COMMITTED || this == ABORTED;
  }
}
