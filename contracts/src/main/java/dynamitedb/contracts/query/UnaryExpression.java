package dynamitedb.contracts.query;

import java.util.Objects;

public record UnaryExpression(UnaryOp operator, Expression operand) implements Expression {
  public UnaryExpression {
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(operand, "operand");
  }
}
