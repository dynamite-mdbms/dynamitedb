package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.PrimaryKey;
import java.util.List;

/**
 * CREATE TABLE as written. {@code primaryKeys} keeps every PRIMARY KEY declaration, in order, so
 * the binder can reject more than one.
 */
public record CreateTableStatement(
    String table, List<Column> columns, List<PrimaryKey> primaryKeys, List<ForeignKey> foreignKeys)
    implements Statement {

  public CreateTableStatement {
    columns = List.copyOf(columns);
    primaryKeys = List.copyOf(primaryKeys);
    foreignKeys = List.copyOf(foreignKeys);
  }
}
