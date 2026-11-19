package dynamitedb.contracts.query;

import java.util.List;
import java.util.Optional;

public record SelectStatement(
    List<SelectItem> items,
    FromSource from,
    Optional<Expression> predicate,
    List<ColumnRef> groupBy,
    Optional<Expression> having,
    List<SortKey> orderBy)
    implements Statement {

  public SelectStatement {
    items = List.copyOf(items);
    groupBy = List.copyOf(groupBy);
    orderBy = List.copyOf(orderBy);
  }

  public SelectStatement(
      List<ColumnRef> projection,
      FromSource from,
      Optional<Expression> predicate,
      List<SortKey> orderBy) {
    this(
        projection.stream()
            .map(column -> new SelectItem(new ColumnExpression(column), Optional.empty()))
            .toList(),
        from,
        predicate,
        List.of(),
        Optional.empty(),
        orderBy);
  }

  public List<ColumnRef> projection() {
    return java.util.stream.IntStream.range(0, items.size())
        .mapToObj(
            index -> {
              SelectItem item = items.get(index);
              if (item.expression() instanceof ColumnExpression column) return column.column();
              String name = item.alias().orElse("__select_item_" + index);
              return new ColumnRef(Optional.empty(), name);
            })
        .toList();
  }
}
