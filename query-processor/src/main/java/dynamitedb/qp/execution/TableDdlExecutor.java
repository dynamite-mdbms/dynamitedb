package dynamitedb.qp.execution;

import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.TableAdmin;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateTablePlan;
import dynamitedb.contracts.query.DropTablePlan;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.Schema;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Executes durable table catalog changes while CCM excludes active transactions. */
public final class TableDdlExecutor {
  private static final Schema NO_COLUMNS = new Schema(List.of());

  private final TableAdmin tables;
  private final RecoveryCoordinator recovery;

  public TableDdlExecutor(TableAdmin tables, RecoveryCoordinator recovery) {
    this.tables = Objects.requireNonNull(tables, "tables");
    this.recovery = Objects.requireNonNull(recovery, "recovery");
  }

  @SuppressWarnings("try") // The maintenance guard is held for the whole catalog mutation.
  public QueryResult execute(Supplier<PreparedQuery> prepare) {
    try (MaintenanceGuard ignored = recovery.acquireMaintenance()) {
      PreparedQuery prepared = Objects.requireNonNull(prepare.get(), "prepared");
      if (prepared.root() instanceof CreateTablePlan create) {
        tables.createTable(create.definition());
        return new QueryResult(
            NO_COLUMNS, List.of(), 0, "Table " + create.definition().name() + " created");
      }
      if (prepared.root() instanceof DropTablePlan drop) {
        tables.dropTable(drop.table(), drop.behavior());
        return new QueryResult(NO_COLUMNS, List.of(), 0, "Table " + drop.table() + " dropped");
      }
      throw new DbmsException(ErrorCode.INVALID_PLAN, "DDL preparation returned a non-DDL plan");
    }
  }
}
