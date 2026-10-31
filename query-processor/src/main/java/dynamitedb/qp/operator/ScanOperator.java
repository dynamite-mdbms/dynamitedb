package dynamitedb.qp.operator;

import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Versi Mock untuk ScanOperator (QP-05): Mengembalikan data dummy lokal. */
public class ScanOperator implements Operator {

  private final String table;
  private final String alias;
  private final Schema schema;
  private List<OperatorRow> mockRows;
  private int currentIndex;

  public ScanOperator(
      TupleReader source,
      String table,
      String alias,
      Schema tableSchema,
      TransactionId tx,
      TransactionCoordinator coordinator) {
    this.table = Objects.requireNonNull(table, "table");
    this.alias = Objects.requireNonNull(alias, "alias");
    this.schema = tableSchema;

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
