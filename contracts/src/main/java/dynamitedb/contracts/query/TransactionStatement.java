package dynamitedb.contracts.query;

public record TransactionStatement(TransactionCommand command) implements Statement {}
