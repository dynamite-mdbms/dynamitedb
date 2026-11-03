package dynamitedb.contracts.query;

import java.util.List;

public record InsertStatement(String table, List<Expression> values) implements Statement {

  public InsertStatement {
    values = List.copyOf(values);
  }
}
