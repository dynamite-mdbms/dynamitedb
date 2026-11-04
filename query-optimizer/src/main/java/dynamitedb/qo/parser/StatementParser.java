package dynamitedb.qo.parser;

import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableSource;
import java.util.List;
import java.util.Optional;

/** Mengenali struktur statement dari token (QO-02, QO-03, QO-05, QO-06). */
public class StatementParser {

  public Statement parseStatement(List<Token> tokens) {
    // TODO: SELECT, INSERT, UPDATE, DELETE, CREATE INDEX, BEGIN/COMMIT/ABORT
    return new SelectStatement(
        List.of(new ColumnRef(Optional.empty(), "*")),
        new TableSource("mock", "mock"),
        Optional.empty(),
        List.of());
  }
}
