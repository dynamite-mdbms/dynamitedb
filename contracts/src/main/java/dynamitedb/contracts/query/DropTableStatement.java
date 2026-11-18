package dynamitedb.contracts.query;

public record DropTableStatement(String table, DropBehavior behavior) implements Statement {}
