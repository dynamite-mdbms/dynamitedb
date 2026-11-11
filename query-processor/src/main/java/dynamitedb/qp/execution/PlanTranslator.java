package dynamitedb.qp.execution;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.BlockNestedLoopJoinOperator;
import dynamitedb.qp.operator.FilterOperator;
import dynamitedb.qp.operator.IndexScanOperator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.operator.ProjectOperator;
import dynamitedb.qp.operator.ScanOperator;
import dynamitedb.qp.operator.SortOperator;
import dynamitedb.qp.transaction.TransactionCoordinator;

/** Ubah plan dari QO jadi pohon operator (QP-03). */
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

  // child diterjemahin dulu, baru dibungkus operator induknya
  public Operator translate(PlanNode plan, TransactionId tx) {
    return switch (plan) {
      case TableScanPlan scan ->
          new ScanOperator(
              reader, scan.table(), scan.alias(), catalog.getSchema(scan.table()), tx, coordinator);
      case IndexScanPlan scan ->
          new IndexScanOperator(
              reader,
              scan.index(),
              scan.predicate(),
              scan.table(),
              scan.alias(),
              catalog.getSchema(scan.table()),
              tx,
              coordinator,
              scan.residual(),
              evaluator);
      case FilterPlan filter ->
          new FilterOperator(translate(filter.child(), tx), filter.predicate(), evaluator);
      case ProjectPlan project ->
          new ProjectOperator(translate(project.child(), tx), project.columns());
      case JoinPlan join ->
          new BlockNestedLoopJoinOperator(
              translate(join.left(), tx),
              translate(join.right(), tx),
              join.kind(),
              join.condition(),
              evaluator);
      case SortPlan sort -> new SortOperator(translate(sort.child(), tx), sort.keys());
      // DML, DDL, sama perintah transaksi udah diarahin ExecutionEngine, ga boleh sampe sini
      case InsertPlan insert -> throw notRelational(plan);
      case UpdatePlan update -> throw notRelational(plan);
      case DeletePlan delete -> throw notRelational(plan);
      case CreateIndexPlan index -> throw notRelational(plan);
      case TransactionCommandPlan command -> throw notRelational(plan);
    };
  }

  private static DbmsException notRelational(PlanNode plan) {
    return new DbmsException(
        ErrorCode.INVALID_PLAN,
        plan.getClass().getSimpleName() + " is not a relational plan and cannot be translated");
  }
}
