package dynamitedb.contracts.query;

/**
 * Single-column FOREIGN KEY: {@code column} references {@code referencedTable(referencedColumn)}.
 */
public record ForeignKey(String column, String referencedTable, String referencedColumn) {}
