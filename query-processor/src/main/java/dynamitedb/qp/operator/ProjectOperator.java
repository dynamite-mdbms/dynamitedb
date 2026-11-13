package dynamitedb.qp.operator;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ProjectOperator implements Operator {

  private final Operator child;
  private final List<ColumnRef> columns;
  private int[] projectionIndices;
  private Schema outputSchema;

  public ProjectOperator(Operator child, List<ColumnRef> columns) {
    this.child = Objects.requireNonNull(child, "child");
    this.columns = List.copyOf(columns);
  }

  @Override
  public void open() {
    child.open();
    projectionIndices = null;
    outputSchema = null;
  }

  @Override
  public Optional<OperatorRow> next() {
    Optional<OperatorRow> nextRow = child.next();
    if (nextRow.isEmpty()) {
      return Optional.empty();
    }

    OperatorRow inputRow = nextRow.get();

    if (projectionIndices == null) {
      buildProjectionMapping(inputRow.schema());
    }

    List<Value> projectedValues = new ArrayList<>(projectionIndices.length);
    List<Value> inputValues = inputRow.row().values();

    for (int index : projectionIndices) {
      projectedValues.add(inputValues.get(index));
    }

    return Optional.of(new OperatorRow(new Row(projectedValues), outputSchema, inputRow.origins()));
  }

  private void buildProjectionMapping(Schema inputSchema) {
    List<Column> inputCols = inputSchema.columns();
    projectionIndices = new int[columns.size()];
    List<Column> outputCols = new ArrayList<>(columns.size());

    for (int i = 0; i < columns.size(); i++) {
      ColumnRef ref = columns.get(i);
      int matchedIndex = -1;
      int matchCount = 0;

      for (int j = 0; j < inputCols.size(); j++) {
        Column col = inputCols.get(j);
        boolean colMatches = col.name() != null && col.name().equalsIgnoreCase(ref.column());
        boolean relMatches =
            ref.relation().isEmpty()
                || (col.relation() != null
                    && col.relation().equalsIgnoreCase(ref.relation().get()));
        if (colMatches && relMatches) {
          matchedIndex = j;
          matchCount++;
        }
      }

      if (matchCount == 0) {
        throw new DbmsException(ErrorCode.SQL_BIND, "Column not found in input schema: " + ref);
      }
      if (matchCount > 1) {
        throw new DbmsException(ErrorCode.SQL_BIND, "Ambiguous column reference: " + ref);
      }

      projectionIndices[i] = matchedIndex;
      outputCols.add(inputCols.get(matchedIndex));
    }

    outputSchema = new Schema(outputCols);
  }

  @Override
  public void close() {
    child.close();
    projectionIndices = null;
    outputSchema = null;
  }
}
