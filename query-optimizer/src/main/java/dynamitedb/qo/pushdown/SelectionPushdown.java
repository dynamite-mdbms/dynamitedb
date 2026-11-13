package dynamitedb.qo.pushdown;

import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.pipeline.PlanRewrite;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mendorong seleksi sedekat mungkin ke tabel sumber (QO-09).
 *
 * <p>Every join kind here (CARTESIAN, THETA, NATURAL) is an inner join, so a conjunct that only
 * reads one side can move below the join without changing which rows qualify.
 */
public class SelectionPushdown implements PlanRewrite {

  @Override
  public PlanNode rewrite(PlanNode plan) {
    return switch (plan) {
      case FilterPlan filter -> {
        PlanNode result = rewrite(filter.child());
        List<Expression> conjuncts = conjuncts(filter.predicate());
        // Pushed in reverse so the first WHERE conjunct sits deepest and is evaluated first.
        for (int i = conjuncts.size() - 1; i >= 0; i--) result = push(result, conjuncts.get(i));
        yield result;
      }
      case ProjectPlan project -> new ProjectPlan(rewrite(project.child()), project.columns());
      case SortPlan sort -> new SortPlan(rewrite(sort.child()), sort.keys());
      case JoinPlan join ->
          new JoinPlan(rewrite(join.left()), rewrite(join.right()), join.kind(), join.condition());
      case UpdatePlan update ->
          new UpdatePlan(rewrite(update.source()), update.table(), update.target(), update.value());
      case DeletePlan delete -> new DeletePlan(rewrite(delete.source()), delete.table());
      default -> plan;
    };
  }

  /** Places one single-condition selection at the lowest node that still sees all its columns. */
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
        // Reads both sides: stays above the join for JoinRewriter (QO-11) to merge.
        yield new FilterPlan(join, predicate);
      }
      // Conjunctive selections commute with each other, with sorting and with projection.
      case FilterPlan filter -> new FilterPlan(push(filter.child(), predicate), filter.predicate());
      case SortPlan sort -> new SortPlan(push(sort.child(), predicate), sort.keys());
      case ProjectPlan project ->
          new ProjectPlan(push(project.child(), predicate), project.columns());
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
      case ColumnExpression column -> List.of(column.column());
      case BinaryExpression binary -> {
        List<ColumnRef> all = new ArrayList<>(columns(binary.left()));
        all.addAll(columns(binary.right()));
        yield all;
      }
      case LiteralExpression literal -> List.of();
      // kolom di dalam subquery milik scope subquery, bukan query luar
      case InSubqueryExpression in -> columns(in.operand());
    };
  }

  private static Set<String> relations(Expression predicate) {
    // An unbound column has no relation, and "" never matches an alias, so it is never pushed.
    return columns(predicate).stream()
        .map(c -> c.relation().orElse(""))
        .collect(Collectors.toSet());
  }
}
