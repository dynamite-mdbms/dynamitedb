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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;







public class SelectionPushdown implements PlanRewrite {

  @Override
  public PlanNode rewrite(PlanNode plan) {
    return switch (plan) {
      case FilterPlan filter -> {
        PlanNode result = rewrite(filter.child());
        List<Expression> conjuncts = conjuncts(filter.predicate());

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
          new JoinPlan(rewrite(join.left()), rewrite(join.right()), join.kind(), join.condition());
      case UpdatePlan update ->
          new UpdatePlan(rewrite(update.source()), update.table(), update.assignments());
      case DeletePlan delete -> new DeletePlan(rewrite(delete.source()), delete.table());
      default -> plan;
    };
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
