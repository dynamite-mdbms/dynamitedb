package dynamitedb.qp.operator;

import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.Objects;
import java.util.Optional;

public class FilterOperator implements Operator {

  private final Operator child;

  public FilterOperator(Operator child, Expression predicate, ExpressionEvaluator evaluator) {
    this.child = Objects.requireNonNull(child, "child");
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
