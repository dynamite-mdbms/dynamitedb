package dynamitedb.qp.operator;

import dynamitedb.contracts.query.OperatorRow;
import java.util.Objects;
import java.util.Optional;

public class LimitOperator implements Operator {
  private final Operator child;
  private final long count;
  private long emitted;

  public LimitOperator(Operator child, long count) {
    this.child = Objects.requireNonNull(child, "child");
    if (count < 0) throw new IllegalArgumentException("LIMIT must not be negative");
    this.count = count;
  }

  @Override
  public void open() {
    child.open();
    emitted = 0;
  }

  @Override
  public Optional<OperatorRow> next() {
    if (emitted >= count) return Optional.empty();
    Optional<OperatorRow> row = child.next();
    if (row.isPresent()) emitted++;
    return row;
  }

  @Override
  public void close() {
    child.close();
  }
}
