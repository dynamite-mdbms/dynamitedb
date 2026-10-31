package dynamitedb.qp.operator;

import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.List;
import java.util.Map;
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
  private List<OperatorRow> mockRows;
  private int currentIndex;

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
    this.schema = Objects.requireNonNull(tableSchema, "tableSchema");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.residual = Objects.requireNonNull(residual, "residual");
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    this.mockRows = List.of();
    this.currentIndex = 0;
  }

  @Override
  public void open() {
    this.currentIndex = 0;
    this.mockRows = List.of(new OperatorRow(new Row(List.of()), schema, Map.of(alias, null)));
  }

  @Override
  public Optional<OperatorRow> next() {
    if (currentIndex < mockRows.size()) {
      return Optional.of(mockRows.get(currentIndex++));
    }
    return Optional.empty();
  }

  @Override
  public void close() {
    this.currentIndex = 0;
  }
}
