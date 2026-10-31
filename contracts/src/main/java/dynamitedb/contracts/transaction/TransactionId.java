package dynamitedb.contracts.transaction;

public record TransactionId(long value) implements Comparable<TransactionId> {
  @Override
  public int compareTo(TransactionId other) {
    return Long.compare(value, other.value);
  }

  @Override
  public String toString() {
    return "T" + value;
  }
}
