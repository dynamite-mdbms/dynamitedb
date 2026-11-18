package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.DropBehavior;

public record DropTableStatement(String table, DropBehavior behavior) implements Statement {}
