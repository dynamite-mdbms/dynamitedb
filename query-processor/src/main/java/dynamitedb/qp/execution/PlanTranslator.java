package dynamitedb.qp.execution;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.transaction.TransactionCoordinator;

/** Ubah plan dari QO jadi pohon operator (QP-03). */
// udah sesuai CD final
public class PlanTranslator {

  private final ExpressionEvaluator evaluator;
  private final TupleReader reader;
  private final CatalogAccess catalog;
  private final TransactionCoordinator coordinator;

  public PlanTranslator(
      ExpressionEvaluator evaluator,
      TupleReader reader,
      CatalogAccess catalog,
      TransactionCoordinator coordinator) {
    this.evaluator = evaluator;
    this.reader = reader;
    this.catalog = catalog;
    this.coordinator = coordinator;
  }

  public Operator translate(PlanNode plan, TransactionId tx) {
    // TODO: mapping tiap PlanNode relasional ke operator QP3/QP4, sisanya ditolak
    return null;
  }
}
