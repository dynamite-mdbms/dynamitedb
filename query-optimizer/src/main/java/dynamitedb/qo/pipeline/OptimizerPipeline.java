package dynamitedb.qo.pipeline;

import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.ExistsSubqueryExpression;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.ScalarSubqueryExpression;
import dynamitedb.contracts.query.SelectItem;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.UnaryExpression;
import dynamitedb.contracts.query.UpdateAssignment;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.GeneticJoinOrderOptimizer;
import dynamitedb.qo.join.JoinOrderOptimizer;
import java.util.List;
import java.util.Objects;

public class OptimizerPipeline {

  private final List<PlanRewrite> rewrites;
  private final JoinOrderOptimizer joinEnumerator;
  private final BestPlanSelector selector;
  private final GeneticJoinOrderOptimizer geneticJoinOrderOptimizer;

  public OptimizerPipeline(
      List<PlanRewrite> rewrites, JoinOrderOptimizer joinEnumerator, BestPlanSelector selector) {
    this(rewrites, joinEnumerator, selector, null);
  }

  public OptimizerPipeline(
      List<PlanRewrite> rewrites,
      JoinOrderOptimizer joinEnumerator,
      BestPlanSelector selector,
      GeneticJoinOrderOptimizer geneticJoinOrderOptimizer) {
    this.rewrites = List.copyOf(rewrites);
    this.joinEnumerator = Objects.requireNonNull(joinEnumerator, "joinEnumerator");
    this.selector = Objects.requireNonNull(selector, "selector");
    this.geneticJoinOrderOptimizer = geneticJoinOrderOptimizer;
  }

  public PlanNode optimize(PlanNode plan) {
    PlanNode rewritten = withOptimizedSubqueries(plan);
    for (PlanRewrite rewrite : rewrites) rewritten = rewrite.rewrite(rewritten);
    if (geneticJoinOrderOptimizer != null) {
      return geneticJoinOrderOptimizer.optimize(rewritten).bestPlan();
    }
    List<PlanNode> candidates = joinEnumerator.enumerate(rewritten);
    return candidates.isEmpty() ? rewritten : selector.choose(candidates);
  }

  /** Each uncorrelated subquery plan is optimized on its own, like a top-level SELECT. */
  private PlanNode withOptimizedSubqueries(PlanNode plan) {
    return switch (plan) {
      case FilterPlan filter ->
          new FilterPlan(withOptimizedSubqueries(filter.child()), subqueries(filter.predicate()));
      case JoinPlan join ->
          new JoinPlan(
              withOptimizedSubqueries(join.left()),
              withOptimizedSubqueries(join.right()),
              join.kind(),
              join.condition().map(this::subqueries));
      case ProjectPlan project ->
          ProjectPlan.fromItems(
              withOptimizedSubqueries(project.child()),
              project.items().stream()
                  .map(item -> new SelectItem(subqueries(item.expression()), item.alias()))
                  .toList());
      case SortPlan sort -> new SortPlan(withOptimizedSubqueries(sort.child()), sort.keys());
      case LimitPlan limit -> new LimitPlan(withOptimizedSubqueries(limit.child()), limit.count());
      case AggregatePlan aggregate ->
          new AggregatePlan(
              withOptimizedSubqueries(aggregate.child()),
              aggregate.groupBy(),
              aggregate.aggregates());
      case UpdatePlan update ->
          new UpdatePlan(
              withOptimizedSubqueries(update.source()),
              update.table(),
              update.assignments().stream()
                  .map(a -> new UpdateAssignment(a.target(), subqueries(a.expression())))
                  .toList());
      case DeletePlan delete ->
          new DeletePlan(withOptimizedSubqueries(delete.source()), delete.table());
      // Scans, INSERT, DDL and transaction commands carry no subqueries before rewriting.
      default -> plan;
    };
  }

  private Expression subqueries(Expression expression) {
    return switch (expression) {
      case InSubqueryExpression in ->
          new InSubqueryExpression(
              subqueries(in.operand()), in.query(), in.plan().map(this::optimize), in.negated());
      case ExistsSubqueryExpression exists ->
          new ExistsSubqueryExpression(
              exists.query(), exists.plan().map(this::optimize), exists.negated());
      case ScalarSubqueryExpression scalar ->
          new ScalarSubqueryExpression(scalar.query(), scalar.plan().map(this::optimize));
      case BinaryExpression binary ->
          new BinaryExpression(
              subqueries(binary.left()), binary.operator(), subqueries(binary.right()));
      case UnaryExpression unary ->
          new UnaryExpression(unary.operator(), subqueries(unary.operand()));
      default -> expression;
    };
  }
}
