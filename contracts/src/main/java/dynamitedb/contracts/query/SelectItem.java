package dynamitedb.contracts.query;

import java.util.Objects;
import java.util.Optional;


public record SelectItem(Expression expression, Optional<String> alias) {
  public SelectItem {
    Objects.requireNonNull(expression, "expression");
    alias = Objects.requireNonNull(alias, "alias");
  }
}
