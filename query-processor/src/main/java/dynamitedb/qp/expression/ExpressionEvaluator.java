package dynamitedb.qp.expression;

import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.Value;

/** Ngitung nilai ekspresi atau kondisi buat satu baris (QP-04). */
public class ExpressionEvaluator {

  public Value evaluate(Expression expr, OperatorRow row) {
    // TODO: aritmatika + perbandingan, termasuk NULL
    return new BooleanValue(true);
  }

  public boolean evaluatePredicate(Expression expr, OperatorRow row) {
    // TODO: kondisi + AND, lolos cuma kalau hasilnya TRUE
    return true;
  }
}
