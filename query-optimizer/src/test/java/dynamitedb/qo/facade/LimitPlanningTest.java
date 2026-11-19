package dynamitedb.qo.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.qo.verification.QueryOptimizerDriver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** LIMIT stays the root: every rewrite runs below it exactly as it would without LIMIT. */
class LimitPlanningTest {

  private final QueryOptimizer optimizer = QueryOptimizerDriver.standaloneOptimizer();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT name FROM student WHERE gpa > 3.5",
        "SELECT name FROM student WHERE student_id = 1 ORDER BY name",
        "SELECT student.name, course.title FROM student, enrollment, course "
            + "WHERE student.student_id = enrollment.student_id "
            + "AND enrollment.course_id = course.course_id AND course.credits = 4 "
            + "ORDER BY student.name DESC",
        "SELECT student_id, grade FROM student NATURAL JOIN enrollment ORDER BY student_id ASC",
        "SELECT name FROM student WHERE student_id IN (SELECT student_id FROM enrollment)",
      })
  void optimizesBelowLimitWithoutMovingIt(String sql) {
    for (long count : new long[] {0, 1, 1_000_000}) {
      PreparedQuery plain = optimizer.prepare(sql);
      PreparedQuery limited = optimizer.prepare(sql + " LIMIT " + count);

      assertEquals(new LimitPlan(plain.root(), count), limited.root(), sql);
      assertEquals(plain.outputSchema(), limited.outputSchema(), sql);
      assertEquals(1, limits(limited.root()), sql);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ASC", "DESC"})
  void keepsSortAndPushedSelectionsBelowLimit(String direction) {
    PlanNode root =
        optimizer
            .prepare(
                "SELECT student.name FROM student JOIN enrollment "
                    + "ON student.student_id = enrollment.student_id "
                    + "WHERE enrollment.grade = 'A' ORDER BY student.gpa "
                    + direction
                    + " LIMIT 2")
            .root();

    LimitPlan limit = assertInstanceOf(LimitPlan.class, root);
    ProjectPlan project = assertInstanceOf(ProjectPlan.class, limit.child());
    SortPlan sort = assertInstanceOf(SortPlan.class, project.child());
    assertEquals(direction, sort.keys().get(0).direction().name());
    // The WHERE selection was pushed into one join input, and the join may be reordered.
    JoinPlan join = assertInstanceOf(JoinPlan.class, sort.child());
    assertTrue(contains(join, FilterPlan.class), join.toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT name FROM student LIMIT 5",
        "SELECT name FROM student WHERE student_id = 7 ORDER BY name LIMIT 1",
      })
  void explainShowsLimitBeforeAndAfterOptimization(String sql) {
    var explanation = optimizer.explain(sql);

    assertTrue(explanation.before().startsWith("Limit [count="), explanation.before());
    assertTrue(explanation.after().startsWith("Limit [count="), explanation.after());
  }

  private static int limits(PlanNode plan) {
    return switch (plan) {
      case LimitPlan limit -> 1 + limits(limit.child());
      case ProjectPlan project -> limits(project.child());
      case SortPlan sort -> limits(sort.child());
      case FilterPlan filter -> limits(filter.child());
      case JoinPlan join -> limits(join.left()) + limits(join.right());
      default -> 0;
    };
  }

  private static boolean contains(PlanNode plan, Class<? extends PlanNode> type) {
    if (type.isInstance(plan)) return true;
    return switch (plan) {
      case LimitPlan limit -> contains(limit.child(), type);
      case ProjectPlan project -> contains(project.child(), type);
      case SortPlan sort -> contains(sort.child(), type);
      case FilterPlan filter -> contains(filter.child(), type);
      case JoinPlan join -> contains(join.left(), type) || contains(join.right(), type);
      default -> false;
    };
  }
}
