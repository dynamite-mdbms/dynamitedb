package dynamitedb.qp.execution;

import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.qp.operator.Operator;
import java.util.List;

public class ReadExecutor {

  public QueryResult execute(Operator root, Schema outputSchema) {
    return new QueryResult(outputSchema, List.of(), 0, "0 row(s) (Mock)");
  }
}
