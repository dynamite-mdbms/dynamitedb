package dynamitedb.qp.operator;

import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.Objects;
import java.util.Optional;

public class FilterOperator implements Operator {

  private final Operator child;
  private final Expression predicate;
  private final ExpressionEvaluator evaluator;

  public FilterOperator(Operator child, Expression predicate, ExpressionEvaluator evaluator) {
    this.child = Objects.requireNonNull(child, "child");
    this.predicate = Objects.requireNonNull(predicate, "predicate");
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
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
