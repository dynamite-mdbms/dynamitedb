package dynamitedb.contracts.query;

import java.util.Optional;

public record UpdateStatement(
    String table, ColumnRef target, Expression expression, Optional<Expression> predicate)
    implements Statement {}
