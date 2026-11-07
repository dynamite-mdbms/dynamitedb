package dynamitedb.qp.execution;

import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.qp.operator.Operator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ReadExecutor {

  public QueryResult execute(Operator root, Schema outputSchema) {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(outputSchema, "outputSchema");

    List<Row> rows = new ArrayList<>();

    try {
      root.open();

      while (true) {
        Optional<OperatorRow> opRow = root.next();
        if (opRow.isEmpty()) {
          break; // Data habis
        }
        rows.add(opRow.get().row());
      }
    } finally {
      root.close();
    }

    String message = rows.size() + " row(s) returned";
    return new QueryResult(outputSchema, rows, 0, message);
  }
}
