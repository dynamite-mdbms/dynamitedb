package dynamitedb.qp.execution;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.CreateTablePlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.DropTablePlan;
import dynamitedb.contracts.query.ExistsSubqueryExpression;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.ScalarSubqueryExpression;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UnaryExpression;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.AggregateOperator;
import dynamitedb.qp.operator.BlockNestedLoopJoinOperator;
import dynamitedb.qp.operator.FilterOperator;
import dynamitedb.qp.operator.IndexScanOperator;
import dynamitedb.qp.operator.LimitOperator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.operator.ProjectOperator;
import dynamitedb.qp.operator.ScanOperator;
import dynamitedb.qp.operator.SortOperator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
              evaluatorFor(scan.residual(), tx));
      case FilterPlan filter ->
          new FilterOperator(
              translate(filter.child(), tx),
              filter.predicate(),
              evaluatorFor(Optional.of(filter.predicate()), tx));
      case ProjectPlan project -> translateProject(project, tx);
      case AggregatePlan aggregate ->
          new AggregateOperator(translate(aggregate.child(), tx), aggregate);
      case JoinPlan join ->
          new BlockNestedLoopJoinOperator(
              translate(join.left(), tx),
              translate(join.right(), tx),
              join.kind(),
              join.condition(),
              evaluatorFor(join.condition(), tx));
      case SortPlan sort -> new SortOperator(translate(sort.child(), tx), sort.keys());
      case LimitPlan limit -> new LimitOperator(translate(limit.child(), tx), limit.count());
      // DML, DDL, sama perintah transaksi udah diarahin ExecutionEngine, ga boleh sampe sini
      case InsertPlan insert -> throw notRelational(plan);
      case UpdatePlan update -> throw notRelational(plan);
      case DeletePlan delete -> throw notRelational(plan);
      case CreateIndexPlan index -> throw notRelational(plan);
      case CreateTablePlan create -> throw notRelational(plan);
      case DropTablePlan drop -> throw notRelational(plan);
      case TransactionCommandPlan command -> throw notRelational(plan);
    };
  }

  private Operator translateProject(ProjectPlan project, TransactionId tx) {
    return new ProjectOperator(
        translate(project.child(), tx),
        project.items(),
        evaluatorFor(project.items().stream().map(item -> item.expression()).toList(), tx));
  }

  // evaluator per operator supaya hasil subquery ga kebagi antar query/transaksi
  private ExpressionEvaluator evaluatorFor(Optional<Expression> expression, TransactionId tx) {
    return evaluatorFor(expression.stream().toList(), tx);
  }

  ExpressionEvaluator evaluatorFor(List<Expression> expressions, TransactionId tx) {
    if (expressions.stream().noneMatch(PlanTranslator::hasSubquery)) return evaluator;
    return new ExpressionEvaluator(
        subplan -> materialize(subplan, tx),
        subplan -> scalarValue(subplan, tx),
        subplan -> hasRows(subplan, tx));
  }

  private static boolean hasSubquery(Expression expression) {
    return switch (expression) {
      case InSubqueryExpression in -> true;
      case ExistsSubqueryExpression ignored -> true;
      case ScalarSubqueryExpression ignored -> true;
      case BinaryExpression binary -> hasSubquery(binary.left()) || hasSubquery(binary.right());
      case UnaryExpression unary -> hasSubquery(unary.operand());
      default -> false;
    };
  }

  // IN dan scalar subquery punya satu kolom; scalar memeriksa kardinalitas di evaluator.
  private List<Value> materialize(PlanNode subplan, TransactionId tx) {
    Operator root = translate(subplan, tx);
    List<Value> values = new ArrayList<>();
    try {
      root.open();
      for (Optional<OperatorRow> row = root.next(); row.isPresent(); row = root.next()) {
        if (row.get().row().values().size() != 1) {
          throw new DbmsException(
              ErrorCode.INVALID_PLAN, "IN subquery plan must return exactly one output column");
        }
        values.add(row.get().row().values().get(0));
      }
    } finally {
      root.close();
    }
    return values;
  }

  // Scalar query needs at most two rows to distinguish 0, 1, and multiple-row results.
  private Value scalarValue(PlanNode subplan, TransactionId tx) {
    Operator root = translate(subplan, tx);
    try {
      root.open();
      Optional<OperatorRow> first = root.next();
      if (first.isEmpty()) return NullValue.INSTANCE;
      if (first.get().row().values().size() != 1) {
        throw new DbmsException(
            ErrorCode.INVALID_PLAN, "Scalar subquery plan must return exactly one output column");
      }
      Value value = first.get().row().values().getFirst();
      if (root.next().isPresent()) {
        throw new DbmsException(
            ErrorCode.INVALID_STATE, "Scalar subquery returned more than one row");
      }
      return value;
    } finally {
      root.close();
    }
  }

  // EXISTS hanya perlu satu baris, jadi berhenti membaca begitu hasil pertama ditemukan.
  private boolean hasRows(PlanNode subplan, TransactionId tx) {
    Operator root = translate(subplan, tx);
    try {
      root.open();
      return root.next().isPresent();
    } finally {
      root.close();
    }
  }

  private static DbmsException notRelational(PlanNode plan) {
    return new DbmsException(
        ErrorCode.INVALID_PLAN,
        plan.getClass().getSimpleName() + " is not a relational plan and cannot be translated");
  }
}
