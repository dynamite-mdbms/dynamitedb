package dynamitedb.qo.join;

import static dynamitedb.qo.join.Qo5TestSupport.catalog;
import static dynamitedb.qo.join.Qo5TestSupport.column;
import static dynamitedb.qo.join.Qo5TestSupport.columnExpression;
import static dynamitedb.qo.join.Qo5TestSupport.compare;
import static dynamitedb.qo.join.Qo5TestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.qo.binding.CostEstimator;
import java.util.List;
import java.util.Map;
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

    List<PlanNode> candidates = optimizer().enumerate(original);

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

    List<PlanNode> candidates = optimizer().enumerate(original);

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

    List<PlanNode> candidates = optimizer().enumerate(original);

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

    List<PlanNode> candidates = optimizer().enumerate(original);

    assertEquals(2, candidates.size());
    assertEquals(
        new JoinPlan(course, student, JoinKind.CARTESIAN, Optional.empty()), candidates.get(1));
  }

  @Test
  void naturalJoinYieldsOriginalAndSwappedOrdersAndStaysNatural() {
    PlanNode student = scan("student", "s");
    PlanNode enrollment = scan("enrollment", "e");
    JoinPlan original = natural(student, enrollment);

    List<PlanNode> candidates = optimizer().enumerate(original);

    assertEquals(List.of(original, natural(enrollment, student)), candidates);
  }

  @Test
  void swappedNaturalJoinReadsSharedColumnFromItsNewLeftSide() {
    PlanNode student = scan("student", "s");
    PlanNode enrollment = scan("enrollment", "e");
    PlanNode original = selectAbove(natural(student, enrollment), "s");

    List<PlanNode> candidates = optimizer().enumerate(original);

    // QP drops the right-hand student_id, so after the swap only e.student_id exists.
    assertEquals(List.of(original, selectAbove(natural(enrollment, student), "e")), candidates);
  }

  @Test
  void threeWayNaturalChainSwapsEachJoinAndChainsRenames() {
    PlanNode student = scan("student", "s");
    PlanNode enrollment = scan("enrollment", "e");
    PlanNode course = scan("course", "c");
    PlanNode original =
        new ProjectPlan(
            natural(natural(student, enrollment), course),
            List.of(column("s", "student_id"), column("e", "course_id"), column("c", "title")));

    List<PlanNode> candidates = optimizer().enumerate(original);

    assertEquals(4, candidates.size());
    assertEquals(original, candidates.get(0));
    assertTrue(
        candidates.contains(
            new ProjectPlan(
                natural(course, natural(enrollment, student)),
                List.of(
                    column("e", "student_id"), column("c", "course_id"), column("c", "title")))));
    assertTrue(candidates.stream().allMatch(JoinOrderOptimizerTest::onlyNaturalJoins));
  }

  @Test
  void doesNotSwapNaturalJoinWhenSharedNameRepeatsOnOneSide() {
    // s.student_id and e1.student_id both match e2.student_id: QP would add an equality if swapped.
    JoinPlan both =
        new JoinPlan(
            scan("student", "s"), scan("enrollment", "e1"), JoinKind.CARTESIAN, Optional.empty());
    PlanNode enrollment = scan("enrollment", "e2");

    List<PlanNode> candidates = optimizer().enumerate(natural(both, enrollment));

    assertEquals(2, candidates.size());
    assertTrue(candidates.stream().allMatch(c -> ((JoinPlan) c).right().equals(enrollment)));
  }

  @Test
  void stopsSwappingNaturalJoinsAboveSixLeaves() {
    PlanNode last = scan("t6", "t6");
    PlanNode chain = natural(naturalChain(6), last);

    List<PlanNode> candidates = optimizer().enumerate(chain);

    // Only the inner six-table chain is reordered: 2^5 orders.
    assertEquals(32, candidates.size());
    assertTrue(candidates.stream().allMatch(c -> ((JoinPlan) c).right().equals(last)));
  }

  @Test
  void bestPlanSelectorPicksCheaperNaturalJoinOrder() {
    CatalogAccess catalog =
        catalog(
            Map.of(
                "student", new TableStatistics(1_000, 100, 50, Map.of("student_id", 1_000L)),
                "enrollment", new TableStatistics(10, 1, 50, Map.of("student_id", 10L))),
            COLUMNS);
    PlanNode student = scan("student", "s");
    PlanNode enrollment = scan("enrollment", "e");
    PlanNode original = selectAbove(natural(student, enrollment), "s");

    PlanNode best =
        new BestPlanSelector(new CostEstimator(catalog))
            .choose(new JoinOrderOptimizer(catalog).enumerate(original));

    // 1 + ceil(10/100) * 100 = 101 blocks beat 100 + ceil(1000/100) * 1 = 110.
    assertEquals(selectAbove(natural(enrollment, student), "e"), best);
  }

  @Test
  void leavesPlansWithoutJoinsUnchanged() {
    PlanNode original = scan("student", "s");

    assertEquals(List.of(original), optimizer().enumerate(original));
  }

  @Test
  void enumeratesBelowProjectAndFilterOperators() {
    PlanNode student = scan("student", "s");
    PlanNode course = scan("course", "c");
    Expression condition =
        compare(columnExpression("s", "id"), BinaryOp.EQ, columnExpression("c", "student_id"));
    JoinPlan join = new JoinPlan(student, course, JoinKind.THETA, Optional.of(condition));
    PlanNode plan = new ProjectPlan(new FilterPlan(join, condition), List.of());

    List<PlanNode> candidates = optimizer().enumerate(plan);

    assertEquals(2, candidates.size());
    assertTrue(candidates.stream().allMatch(ProjectPlan.class::isInstance));
  }

  @Test
  void stopsAboveSixLeaves() {
    PlanNode left = leftDeepChain(6);
    PlanNode right = leftDeepChain(1);
    JoinPlan original = new JoinPlan(left, right, JoinKind.CARTESIAN, Optional.empty());

    assertEquals(List.of(original), optimizer().enumerate(original));
  }

  @Test
  void limitsEnumerationToSixtyFourCandidates() {
    assertEquals(64, optimizer().enumerate(leftDeepChain(6)).size());
  }

  private static final Map<String, List<String>> COLUMNS =
      Map.of(
          "student", List.of("student_id", "name"),
          "enrollment", List.of("student_id", "course_id", "score"),
          "course", List.of("course_id", "title"));

  private static JoinOrderOptimizer optimizer() {
    return new JoinOrderOptimizer(catalog(Map.of(), COLUMNS));
  }

  private static JoinPlan natural(PlanNode left, PlanNode right) {
    return new JoinPlan(left, right, JoinKind.NATURAL, Optional.empty());
  }

  /** SELECT with WHERE and ORDER BY over student_id, read through {@code relation}. */
  private static PlanNode selectAbove(PlanNode join, String relation) {
    Expression filter =
        compare(
            columnExpression(relation, "student_id"), BinaryOp.GT, columnExpression("e", "score"));
    return new ProjectPlan(
        new SortPlan(
            new FilterPlan(join, filter),
            List.of(new SortKey(column(relation, "student_id"), SortDirection.ASC))),
        List.of(column("s", "name"), column(relation, "student_id"), column("e", "course_id")));
  }

  private static PlanNode naturalChain(int leaves) {
    PlanNode current = scan("t0", "t0");
    for (int i = 1; i < leaves; i++) current = natural(current, scan("t" + i, "t" + i));
    return current;
  }

  private static boolean onlyNaturalJoins(PlanNode plan) {
    return switch (plan) {
      case ProjectPlan project -> onlyNaturalJoins(project.child());
      case JoinPlan join ->
          join.kind() == JoinKind.NATURAL
              && onlyNaturalJoins(join.left())
              && onlyNaturalJoins(join.right());
      default -> true;
    };
  }

  private static PlanNode leftDeepChain(int leaves) {
    PlanNode current = scan("t0", "t0");
    for (int i = 1; i < leaves; i++) {
      current = new JoinPlan(current, scan("t" + i, "t" + i), JoinKind.CARTESIAN, Optional.empty());
    }
    return current;
  }
}
