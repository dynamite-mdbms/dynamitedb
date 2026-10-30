package dynamitedb.qp.expression;

import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Value;

/** Ngitung nilai ekspresi atau kondisi buat satu baris (QP-04). */
public class ExpressionEvaluator {

  public Value evaluate(Expression expr, Row row) {
    // TODO: aritmatika + perbandingan
    return new Value(0);
  }

  public boolean evaluatePredicate(Expression expr, Row row) {
    // TODO: kondisi + AND
    return true;
  }
}
