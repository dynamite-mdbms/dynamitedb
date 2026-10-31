package dynamitedb.qp.execution;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;

/** Ubah plan dari QO jadi pohon operator (QP-03). */
public class PlanTranslator {

  // di CD final translator juga pegang TupleReader, CatalogAccess, sama TransactionCoordinator,
  // ditambahin kalau interface/kelasnya udah ada di repo
  private final ExpressionEvaluator evaluator;

  public PlanTranslator(ExpressionEvaluator evaluator) {
    this.evaluator = evaluator;
  }

  public Operator translate(PlanNode plan, TransactionId tx) {
    // TODO: mapping tiap PlanNode relasional ke operator QP3/QP4, sisanya ditolak
    return null;
  }
}
