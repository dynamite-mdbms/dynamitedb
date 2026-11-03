package dynamitedb.contracts.query;

public record TableSource(String table, String alias) implements FromSource {}
