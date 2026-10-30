package dynamitedb.qp.execution;

import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;

/** Ubah plan dari QO jadi pohon operator (QP-03). */
public class PlanTranslator {

  private final ExpressionEvaluator evaluator;

  public PlanTranslator(ExpressionEvaluator evaluator) {
    this.evaluator = evaluator;
  }

  public Operator translate(PlanNode plan, ExecutionContext context) {
    // TODO: mapping tiap PlanNode ke operator QP3/QP4
    return null;
  }
}
