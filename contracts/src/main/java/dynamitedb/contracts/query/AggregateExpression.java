package dynamitedb.contracts.query;

import java.util.Objects;
import java.util.Optional;

public record AggregateExpression(AggregateFunction function, Optional<ColumnRef> argument)
    implements Expression {
  public AggregateExpression {
    Objects.requireNonNull(function, "function");
    argument = Objects.requireNonNull(argument, "argument");
    if (function != AggregateFunction.COUNT && argument.isEmpty()) {
      throw new IllegalArgumentException(function + " requires a column argument");
    }
  }

  public String outputName() {
    return function.name()
        + "("
        + argument
            .map(ref -> ref.relation().map(relation -> relation + ".").orElse("") + ref.column())
            .orElse("*")
        + ")";
  }
}
