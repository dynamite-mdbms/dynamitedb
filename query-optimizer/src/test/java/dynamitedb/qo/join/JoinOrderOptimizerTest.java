package dynamitedb.qo.join;

import static dynamitedb.qo.join.Qo5TestSupport.columnExpression;
import static dynamitedb.qo.join.Qo5TestSupport.compare;
import static dynamitedb.qo.join.Qo5TestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JoinOrderOptimizerTest {

  @Test
  void enumeratesBothOrdersForTwoLeaves() {
    PlanNode student = scan("student", "s");
    PlanNode course = scan("course", "c");
    Expression condition =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("c", "student_id"));
    JoinPlan original = new JoinPlan(student, course, JoinKind.THETA, Optional.of(condition));

    List<PlanNode> candidates = new JoinOrderOptimizer().enumerate(original);

    assertEquals(2, candidates.size());
    assertEquals(original, candidates.get(0));
    assertEquals(
        new JoinPlan(course, student, JoinKind.THETA, Optional.of(condition)), candidates.get(1));
  }

  @Test
  void enumeratesAllOrdersAndGroupingsForThreeLeaves() {
    PlanNode student = scan("student", "s");
    PlanNode course = scan("course", "c");
    PlanNode enrollment = scan("enrollment", "e");
    Expression first =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("e", "student_id"));
    Expression second =
        compare(columnExpression("c", "id"), BinaryOp.EQ, columnExpression("e", "course_id"));
    JoinPlan original =
        new JoinPlan(
            new JoinPlan(student, enrollment, JoinKind.THETA, Optional.of(first)),
            course,
            JoinKind.THETA,
            Optional.of(second));

    List<PlanNode> candidates = new JoinOrderOptimizer().enumerate(original);

    assertEquals(12, candidates.size());
    assertEquals(original, candidates.get(0));
    assertTrue(
        candidates.stream()
            .map(JoinPlan.class::cast)
            .anyMatch(candidate -> candidate.right() instanceof JoinPlan));
  }

  @Test
  void attachesPredicatesAtTheEarliestJoinThatHasBothRelations() {
    PlanNode student = scan("student", "s");
    PlanNode course = scan("course", "c");
    PlanNode enrollment = scan("enrollment", "e");
    Expression first =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("e", "student_id"));
    Expression second =
        compare(columnExpression("c", "id"), BinaryOp.EQ, columnExpression("e", "course_id"));
    JoinPlan original =
        new JoinPlan(
            new JoinPlan(student, enrollment, JoinKind.THETA, Optional.of(first)),
            course,
            JoinKind.THETA,
            Optional.of(second));

    List<PlanNode> candidates = new JoinOrderOptimizer().enumerate(original);

    JoinPlan originalCandidate = assertInstanceOf(JoinPlan.class, candidates.get(0));
    JoinPlan bottom = assertInstanceOf(JoinPlan.class, originalCandidate.left());
    assertEquals(JoinKind.THETA, bottom.kind());
    assertEquals(first, bottom.condition().orElseThrow());

    JoinPlan rightDeep =
        candidates.stream()
            .map(JoinPlan.class::cast)
            .filter(candidate -> candidate.left().equals(course))
            .filter(candidate -> candidate.right() instanceof JoinPlan)
            .findFirst()
            .orElseThrow();
    JoinPlan rightDeepBottom = assertInstanceOf(JoinPlan.class, rightDeep.right());
    assertEquals(first, rightDeepBottom.condition().orElseThrow());
    assertEquals(second, rightDeep.condition().orElseThrow());

    for (PlanNode candidate : candidates) {
      assertInstanceOf(JoinPlan.class, candidate);
      assertEquals(3, leafCount(candidate));
    }
  }

  private static int leafCount(PlanNode node) {
    return switch (node) {
      case JoinPlan join -> leafCount(join.left()) + leafCount(join.right());
      case FilterPlan filter -> leafCount(filter.child());
      case ProjectPlan project -> leafCount(project.child());
      default -> 1;
    };
  }

  @Test
  void preservesCartesianJoinWhenNoPredicateExists() {
    PlanNode student = scan("student", "s");
    PlanNode course = scan("course", "c");
    JoinPlan original = new JoinPlan(student, course, JoinKind.CARTESIAN, Optional.empty());

    List<PlanNode> candidates = new JoinOrderOptimizer().enumerate(original);

    assertEquals(2, candidates.size());
    assertEquals(
        new JoinPlan(course, student, JoinKind.CARTESIAN, Optional.empty()), candidates.get(1));
  }

  @Test
  void doesNotReorderNaturalJoins() {
    PlanNode original =
        new JoinPlan(scan("student", "s"), scan("course", "c"), JoinKind.NATURAL, Optional.empty());

    assertEquals(List.of(original), new JoinOrderOptimizer().enumerate(original));
  }

  @Test
  void leavesPlansWithoutJoinsUnchanged() {
    PlanNode original = scan("student", "s");

    assertEquals(List.of(original), new JoinOrderOptimizer().enumerate(original));
  }

  @Test
  void enumeratesBelowProjectAndFilterOperators() {
    PlanNode student = scan("student", "s");
    PlanNode course = scan("course", "c");
    Expression condition =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("c", "student_id"));
    JoinPlan join = new JoinPlan(student, course, JoinKind.THETA, Optional.of(condition));
    PlanNode plan = new ProjectPlan(new FilterPlan(join, condition), List.of());

    List<PlanNode> candidates = new JoinOrderOptimizer().enumerate(plan);

    assertEquals(2, candidates.size());
    assertTrue(candidates.stream().allMatch(ProjectPlan.class::isInstance));
  }

  @Test
  void stopsAboveSixLeaves() {
    PlanNode left = leftDeepChain(6);
    PlanNode right = leftDeepChain(1);
    JoinPlan original = new JoinPlan(left, right, JoinKind.CARTESIAN, Optional.empty());

    assertEquals(List.of(original), new JoinOrderOptimizer().enumerate(original));
  }

  @Test
  void limitsEnumerationToSixtyFourCandidates() {
    assertEquals(64, new JoinOrderOptimizer().enumerate(leftDeepChain(6)).size());
  }

  private static PlanNode leftDeepChain(int leaves) {
    PlanNode current = scan("t0", "t0");
    for (int i = 1; i < leaves; i++) {
      current = new JoinPlan(current, scan("t" + i, "t" + i), JoinKind.CARTESIAN, Optional.empty());
    }
    return current;
  }
}
