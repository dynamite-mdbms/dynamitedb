package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Column;
import java.util.List;
import java.util.Optional;

/** Validated CREATE TABLE; a primary-key column is already non-nullable in {@code columns}. */
public record CreateTablePlan(
    String table, List<Column> columns, Optional<String> primaryKey, List<ForeignKey> foreignKeys)
    implements PlanNode {

  public CreateTablePlan {
    columns = List.copyOf(columns);
    foreignKeys = List.copyOf(foreignKeys);
  }
}
