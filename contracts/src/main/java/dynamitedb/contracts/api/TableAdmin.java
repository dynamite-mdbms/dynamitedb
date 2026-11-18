package dynamitedb.contracts.api;

import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.TableDefinition;

/** DDL table yang disediakan SM untuk QP (QP-15): durable di catalog sebelum method kembali. */
public interface TableAdmin {
  void createTable(TableDefinition definition);

  void dropTable(String table, DropBehavior behavior);
}
