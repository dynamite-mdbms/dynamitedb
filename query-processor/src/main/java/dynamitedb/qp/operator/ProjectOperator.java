package dynamitedb.qp.operator;

import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.OperatorRow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ProjectOperator implements Operator {

  private final Operator child;
  private final List<ColumnRef> columns;

  public ProjectOperator(Operator child, List<ColumnRef> columns) {
    this.child = Objects.requireNonNull(child, "child");
    this.columns = List.copyOf(columns);
  }

  @Override
  public void open() {
    child.open();
  }

  @Override
  public Optional<OperatorRow> next() {
    return child.next();
  }

  @Override
  public void close() {
    child.close();
  }
}
