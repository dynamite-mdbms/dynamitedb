package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Column;
import java.util.List;

/**
 * CREATE TABLE as written. {@code primaryKey} lists every column declared PRIMARY KEY, in order, so
 * the binder can reject more than one declaration.
 */
public record CreateTableStatement(
    String table, List<Column> columns, List<String> primaryKey, List<ForeignKey> foreignKeys)
    implements Statement {

  public CreateTableStatement {
    columns = List.copyOf(columns);
    primaryKey = List.copyOf(primaryKey);
    foreignKeys = List.copyOf(foreignKeys);
  }
}
