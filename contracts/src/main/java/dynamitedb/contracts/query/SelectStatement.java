package dynamitedb.contracts.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A SELECT query; {@code limit} is the optional non-negative {@code LIMIT n} row cap. */
public record SelectStatement(
    List<ColumnRef> projection,
    FromSource from,
    Optional<Expression> predicate,
    List<SortKey> orderBy,
    Optional<Long> limit)
    implements Statement {

  public SelectStatement {
    projection = List.copyOf(projection);
    orderBy = List.copyOf(orderBy);
    Objects.requireNonNull(limit, "limit");
    if (limit.filter(n -> n < 0).isPresent()) {
      throw new IllegalArgumentException("LIMIT must not be negative");
    }
  }

  /** A SELECT without LIMIT. */
  public SelectStatement(
      List<ColumnRef> projection,
      FromSource from,
      Optional<Expression> predicate,
      List<SortKey> orderBy) {
    this(projection, from, predicate, orderBy, Optional.empty());
  }
}
