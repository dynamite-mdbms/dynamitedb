package dynamitedb.qo.join;

import static dynamitedb.qo.join.Qo5TestSupport.columnExpression;
import static dynamitedb.qo.join.Qo5TestSupport.compare;
import static dynamitedb.qo.join.Qo5TestSupport.literal;
import static dynamitedb.qo.join.Qo5TestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.storage.IntValue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JoinRewriterTest {

  @Test
  void rewritesCartesianProductWithSelectionIntoThetaJoin() {
    PlanNode left = scan("student", "s");
    PlanNode right = scan("course", "c");
    Expression predicate =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("c", "student_id"));
    PlanNode plan =
        new FilterPlan(new JoinPlan(left, right, JoinKind.CARTESIAN, Optional.empty()), predicate);

    PlanNode rewritten = new JoinRewriter().rewrite(plan);

    assertEquals(new JoinPlan(left, right, JoinKind.THETA, Optional.of(predicate)), rewritten);
  }

  @Test
  void mergesSelectionWithExistingThetaCondition() {
    PlanNode left = scan("student", "s");
    PlanNode right = scan("course", "c");
    Expression joinCondition =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("c", "student_id"));
    Expression filterCondition =
        compare(columnExpression("c", "credits"), BinaryOp.GT, literal(new IntValue(3)));
    PlanNode plan =
        new FilterPlan(
            new JoinPlan(left, right, JoinKind.THETA, Optional.of(joinCondition)), filterCondition);

    PlanNode rewritten = new JoinRewriter().rewrite(plan);

    JoinPlan join = assertInstanceOf(JoinPlan.class, rewritten);
    assertEquals(JoinKind.THETA, join.kind());
    BinaryExpression condition =
        assertInstanceOf(BinaryExpression.class, join.condition().orElseThrow());
    assertEquals(BinaryOp.AND, condition.operator());
    assertEquals(joinCondition, condition.left());
    assertEquals(filterCondition, condition.right());
  }

  @Test
  void leavesNaturalJoinSelectionAboveTheJoin() {
    PlanNode left = scan("student", "s");
    PlanNode right = scan("course", "c");
    Expression predicate =
        compare(columnExpression("s", "id"), BinaryOp.EQ, literal(new IntValue(1)));
    PlanNode natural = new JoinPlan(left, right, JoinKind.NATURAL, Optional.empty());
    PlanNode plan = new FilterPlan(natural, predicate);

    PlanNode rewritten = new JoinRewriter().rewrite(plan);

    assertEquals(new FilterPlan(natural, predicate), rewritten);
  }

  @Test
  void rewritesBelowProjectAndSortOperators() {
    PlanNode left = scan("student", "s");
    PlanNode right = scan("course", "c");
    Expression predicate =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("c", "student_id"));
    PlanNode filter =
        new FilterPlan(new JoinPlan(left, right, JoinKind.CARTESIAN, Optional.empty()), predicate);
    List<ColumnRef> columns = List.of(Qo5TestSupport.column("s", "id"));
    PlanNode plan = new ProjectPlan(filter, columns);

    PlanNode rewritten = new JoinRewriter().rewrite(plan);

    ProjectPlan project = assertInstanceOf(ProjectPlan.class, rewritten);
    assertEquals(columns, project.columns());
    assertEquals(
        new JoinPlan(left, right, JoinKind.THETA, Optional.of(predicate)), project.child());
  }
}
