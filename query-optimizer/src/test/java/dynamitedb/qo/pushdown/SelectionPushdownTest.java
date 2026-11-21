package dynamitedb.qo.pushdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelectionPushdownTest {

  static final PlanNode S = new TableScanPlan("student", "s");
  static final PlanNode E = new TableScanPlan("enroll", "e");
  static final PlanNode C = new TableScanPlan("course", "c");

  private final SelectionPushdown pushdown = new SelectionPushdown();

  @Test
  void splitsConjunctsAndPushesEachToItsTable() {
    Expression gpa = compare("s", "gpa", BinaryOp.GT, 3);
    Expression link = compare("s", "sid", BinaryOp.EQ, col("e", "sid"));
    Expression sid = compare("s", "sid", BinaryOp.LT, 10);
    Expression grade = compare("e", "grade", BinaryOp.EQ, 4);
    PlanNode plan = project(new FilterPlan(cartesian(S, E), and(and(gpa, link), and(sid, grade))));

    PlanNode expected =
        project(
            new FilterPlan(
                cartesian(new FilterPlan(new FilterPlan(S, gpa), sid), new FilterPlan(E, grade)),
                link));
    assertEquals(expected, pushdown.rewrite(plan));
  }

  @Test
  void pushesTwoTableConditionOnlyAsFarAsTheJoinCoveringBothTables() {
    Expression link = compare("s", "sid", BinaryOp.EQ, col("e", "sid"));
    Expression title = compare("c", "cid", BinaryOp.EQ, 7);
    PlanNode plan = new FilterPlan(cartesian(cartesian(S, E), C), and(link, title));

    PlanNode expected = cartesian(new FilterPlan(cartesian(S, E), link), new FilterPlan(C, title));
    assertEquals(expected, pushdown.rewrite(plan));
  }

  @Test
  void pushesIsNullPredicateBelowTheJoinToItsTable() {
    Expression gpaIsNull =
        new BinaryExpression(
            col("s", "gpa"), BinaryOp.IS, new LiteralExpression(NullValue.INSTANCE));
    Expression link = compare("s", "sid", BinaryOp.EQ, col("e", "sid"));
    PlanNode plan = project(new FilterPlan(cartesian(S, E), and(gpaIsNull, link)));

    PlanNode expected = project(new FilterPlan(cartesian(new FilterPlan(S, gpaIsNull), E), link));
    assertEquals(expected, pushdown.rewrite(plan));
  }

  @Test
  void splitsDeleteConditionIntoChainedSelections() {
    Expression a = compare("s", "gpa", BinaryOp.LT, 2);
    Expression b = compare("s", "sid", BinaryOp.GT, 5);
    PlanNode plan = new DeletePlan(new FilterPlan(S, and(a, b)), "student");

    assertEquals(
        new DeletePlan(new FilterPlan(new FilterPlan(S, a), b), "student"), pushdown.rewrite(plan));
  }

  @Test
  void leavesUnboundColumnsAboveTheJoin() {
    Expression unbound =
        new BinaryExpression(
            new ColumnExpression(new ColumnRef(Optional.empty(), "gpa")),
            BinaryOp.GT,
            new LiteralExpression(new IntValue(3)));
    PlanNode plan = new FilterPlan(cartesian(S, E), unbound);

    assertEquals(plan, pushdown.rewrite(plan));
  }

  static ProjectPlan project(PlanNode child) {
    return new ProjectPlan(child, List.of(new ColumnRef(Optional.of("s"), "name")));
  }

  static JoinPlan cartesian(PlanNode left, PlanNode right) {
    return new JoinPlan(left, right, JoinKind.CARTESIAN, Optional.empty());
  }

  static Expression and(Expression left, Expression right) {
    return new BinaryExpression(left, BinaryOp.AND, right);
  }

  static Expression compare(String relation, String column, BinaryOp op, int value) {
    return compare(relation, column, op, new LiteralExpression(new IntValue(value)));
  }

  static Expression compare(String relation, String column, BinaryOp op, Expression right) {
    return new BinaryExpression(col(relation, column), op, right);
  }

  static Expression col(String relation, String column) {
    return new ColumnExpression(new ColumnRef(Optional.of(relation), column));
  }
}
