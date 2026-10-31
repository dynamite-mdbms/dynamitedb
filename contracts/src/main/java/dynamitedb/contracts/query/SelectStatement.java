package dynamitedb.contracts.query;

import java.util.List;
import java.util.Optional;

public record SelectStatement(
    List<ColumnRef> projection,
    FromSource from,
    Optional<Expression> predicate,
    List<SortKey> orderBy)
    implements Statement {

  public SelectStatement {
    projection = List.copyOf(projection);
    orderBy = List.copyOf(orderBy);
  }
}
