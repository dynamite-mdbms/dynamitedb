package dynamitedb.qp.operator;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.Objects;
import java.util.Optional;

public class ScanOperator implements Operator {

  private final TupleReader source;
  private final String table;
  private final String alias;
  private final Schema schema;
  private final TransactionId tx;
  private final TransactionCoordinator coordinator;

  private RowCursor cursor;
  private boolean isOpen = false;

  public ScanOperator(
      TupleReader source,
      String table,
      String alias,
      Schema tableSchema,
      TransactionId tx,
      TransactionCoordinator coordinator) {
    this.source = Objects.requireNonNull(source, "source");
    this.table = Objects.requireNonNull(table, "table");
    this.alias = Objects.requireNonNull(alias, "alias");
    this.schema = OperatorRows.qualify(Objects.requireNonNull(tableSchema, "tableSchema"), alias);
    this.tx = Objects.requireNonNull(tx, "tx");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  @Override
  public void open() {
    if (isOpen) {
      close();
    }
    coordinator.authorizeTable(tx, table, AccessMode.READ);
    this.cursor = source.openTableScan(table, tx);
    this.isOpen = true;
  }

  @Override
  public Optional<OperatorRow> next() {
    if (!isOpen) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "ScanOperator must be opened before calling next()");
    }

    Optional<StoredRow> stored = cursor.next();
    if (stored.isPresent()) {
      return Optional.of(OperatorRows.fromStored(stored.get(), schema, alias));
    }

    return Optional.empty();
  }

  @Override
  public void close() {
    if (cursor != null) {
      cursor.close();
      cursor = null;
    }
    isOpen = false;
  }
}
