package dynamitedb.qo.join;

import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.qo.pipeline.PlanRewrite;
import java.util.Optional;

/** Menggabungkan seleksi dengan cartesian product atau join di bawahnya (QO-11). */
public class JoinRewriter implements PlanRewrite {

  @Override
  public PlanNode rewrite(PlanNode plan) {
    return switch (plan) {
      case FilterPlan filter -> rewriteFilter(filter);
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

  private PlanNode rewriteFilter(FilterPlan filter) {
    PlanNode child = rewrite(filter.child());
    if (child instanceof JoinPlan join && join.kind() != JoinKind.NATURAL) {
      Expression condition =
          PlanExpressions.combine(join.condition(), filter.predicate()).orElseThrow();
      return new JoinPlan(join.left(), join.right(), JoinKind.THETA, Optional.of(condition));
    }
    if (child instanceof FilterPlan inner) {
      Expression condition =
          PlanExpressions.combine(Optional.of(inner.predicate()), filter.predicate()).orElseThrow();
      return new FilterPlan(inner.child(), condition);
    }
    return new FilterPlan(child, filter.predicate());
  }
}
