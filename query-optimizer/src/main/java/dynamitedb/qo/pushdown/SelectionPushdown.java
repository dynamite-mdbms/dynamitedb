package dynamitedb.qo.pushdown;

import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.ExistsSubqueryExpression;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.ScalarSubqueryExpression;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.UnaryExpression;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.pipeline.PlanRewrite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class SelectionPushdown implements PlanRewrite {

  private final CostEstimator cost;

  /** Keeps selections in the order the query wrote them. */
  public SelectionPushdown() {
    this(null);
  }

  /** Also reorders each cascade of selections so the most selective one runs first (QO-09). */
  public SelectionPushdown(CostEstimator cost) {
    this.cost = cost;
  }

  @Override
  public PlanNode rewrite(PlanNode plan) {
    return switch (plan) {
      case FilterPlan filter -> {
        PlanNode result = rewrite(filter.child());
        List<Expression> conjuncts = ordered(conjuncts(filter.predicate()), result);

        // Pushed last to first, so the first conjunct ends innermost and is evaluated first.
        for (int i = conjuncts.size() - 1; i >= 0; i--) result = push(result, conjuncts.get(i));
        yield result;
      }
      case ProjectPlan project -> ProjectPlan.fromItems(rewrite(project.child()), project.items());
      case AggregatePlan aggregate ->
          new AggregatePlan(
              rewrite(aggregate.child()), aggregate.groupBy(), aggregate.aggregates());
      case SortPlan sort -> new SortPlan(rewrite(sort.child()), sort.keys());
      case LimitPlan limit -> new LimitPlan(rewrite(limit.child()), limit.count());
      case JoinPlan join ->
          pushCondition(
              new JoinPlan(
                  rewrite(join.left()), rewrite(join.right()), join.kind(), join.condition()));
      case UpdatePlan update ->
          new UpdatePlan(rewrite(update.source()), update.table(), update.assignments());
      case DeletePlan delete -> new DeletePlan(rewrite(delete.source()), delete.table());
      default -> plan;
    };
  }

  /** ON conjuncts that read only one side become selections on that side, like WHERE ones. */
  private PlanNode pushCondition(JoinPlan join) {
    if (join.condition().isEmpty()) return join;
    PlanNode left = join.left();
    PlanNode right = join.right();
    List<Expression> conjuncts = ordered(conjuncts(join.condition().get()), join);
    List<Expression> kept = new ArrayList<>();
    for (int i = conjuncts.size() - 1; i >= 0; i--) {
      Expression conjunct = conjuncts.get(i);
      Set<String> used = relations(conjunct);
      if (CostEstimator.scans(left).keySet().containsAll(used)) left = push(left, conjunct);
      else if (CostEstimator.scans(right).keySet().containsAll(used)) right = push(right, conjunct);
      else kept.add(0, conjunct);
    }
    if (kept.isEmpty()) return new JoinPlan(left, right, JoinKind.CARTESIAN, Optional.empty());
    return new JoinPlan(
        left,
        right,
        join.kind(),
        kept.stream().reduce((a, b) -> new BinaryExpression(a, BinaryOp.AND, b)));
  }

  private List<Expression> ordered(List<Expression> conjuncts, PlanNode scope) {
    if (cost == null) return conjuncts;
    return conjuncts.stream()
        .sorted(Comparator.comparingDouble(c -> cost.selectivity(c, scope)))
        .toList();
  }

  private static PlanNode push(PlanNode node, Expression predicate) {
    return switch (node) {
      case JoinPlan join -> {
        Set<String> used = relations(predicate);
        if (CostEstimator.scans(join.left()).keySet().containsAll(used)) {
          yield new JoinPlan(
              push(join.left(), predicate), join.right(), join.kind(), join.condition());
        }
        if (CostEstimator.scans(join.right()).keySet().containsAll(used)) {
          yield new JoinPlan(
              join.left(), push(join.right(), predicate), join.kind(), join.condition());
        }

        yield new FilterPlan(join, predicate);
      }

      case FilterPlan filter -> new FilterPlan(push(filter.child(), predicate), filter.predicate());
      case SortPlan sort -> new SortPlan(push(sort.child(), predicate), sort.keys());
      case ProjectPlan project ->
          ProjectPlan.fromItems(push(project.child(), predicate), project.items());
      default -> new FilterPlan(node, predicate);
    };
  }

  static List<Expression> conjuncts(Expression predicate) {
    List<Expression> result = new ArrayList<>();
    if (predicate instanceof BinaryExpression binary && binary.operator() == BinaryOp.AND) {
      result.addAll(conjuncts(binary.left()));
      result.addAll(conjuncts(binary.right()));
    } else {
      result.add(predicate);
    }
    return result;
  }

  static List<ColumnRef> columns(Expression expression) {
    return switch (expression) {
      case AggregateExpression aggregate -> aggregate.argument().map(List::of).orElseGet(List::of);
      case ColumnExpression column -> List.of(column.column());
      case BinaryExpression binary -> {
        List<ColumnRef> all = new ArrayList<>(columns(binary.left()));
        all.addAll(columns(binary.right()));
        yield all;
      }
      case UnaryExpression unary -> columns(unary.operand());
      case LiteralExpression literal -> List.of();

      case InSubqueryExpression in -> columns(in.operand());
      case ExistsSubqueryExpression exists -> List.of();
      case ScalarSubqueryExpression scalar -> List.of();
    };
  }

  private static Set<String> relations(Expression predicate) {

    return columns(predicate).stream()
        .map(c -> c.relation().orElse(""))
        .collect(Collectors.toSet());
  }
}
