package dynamitedb.contracts.query;

import java.util.Optional;

public record DeleteStatement(String table, Optional<Expression> predicate) implements Statement {}
