package dynamitedb.qp.operator;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.Objects;
import java.util.Optional;

public class IndexScanOperator implements Operator {

  private final TupleReader source;
  private final String index;
  private final IndexPredicate predicate;
  private final String table;
  private final String alias;
  private final Schema schema;
  private final TransactionId tx;
  private final TransactionCoordinator coordinator;
  private final Optional<Expression> residual;
  private final ExpressionEvaluator evaluator;

  private RowCursor cursor;
  private boolean isOpen = false;

  public IndexScanOperator(
      TupleReader source,
      String index,
      IndexPredicate predicate,
      String table,
      String alias,
      Schema tableSchema,
      TransactionId tx,
      TransactionCoordinator coordinator,
      Optional<Expression> residual,
      ExpressionEvaluator evaluator) {
    this.source = Objects.requireNonNull(source, "source");
    this.index = Objects.requireNonNull(index, "index");
    this.predicate = Objects.requireNonNull(predicate, "predicate");
    this.table = Objects.requireNonNull(table, "table");
    this.alias = Objects.requireNonNull(alias, "alias");
    this.schema = OperatorRows.qualify(Objects.requireNonNull(tableSchema, "tableSchema"), alias);
    this.tx = Objects.requireNonNull(tx, "tx");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.residual = Objects.requireNonNull(residual, "residual");
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
  }

  @Override
  public void open() {
    if (isOpen) {
      close();
    }
    coordinator.authorizeTable(tx, table, AccessMode.READ);
    this.cursor = source.openIndexScan(index, predicate, tx);
    this.isOpen = true;
  }

  @Override
  public Optional<OperatorRow> next() {
    if (!isOpen) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "IndexScanOperator must be opened before calling next()");
    }

    while (true) {
      Optional<StoredRow> stored = cursor.next();
      if (stored.isEmpty()) {
        return Optional.empty();
      }

      OperatorRow row = OperatorRows.fromStored(stored.get(), schema, alias);
      if (residual.isPresent()) {
        boolean passes = evaluator.evaluatePredicate(residual.get(), row);
        if (!passes) {
          continue;
        }
      }

      return Optional.of(row);
    }
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
