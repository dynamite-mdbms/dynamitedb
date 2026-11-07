package dynamitedb.qo.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.Schema;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryOptimizerDriverTest {

  @Test
  void splitsMultilineScriptWithoutBreakingQuotedSemicolon() {
    String script =
        """
        # ignored comment
        -- expect: OK
        SELECT 'a;b'
        FROM student;

        -- expect: SQL_SYNTAX
        SELEC * FROM student;
        """;

    List<String> scenarios = QueryOptimizerDriver.splitSqlScript(script);

    assertEquals(2, scenarios.size());
    assertTrue(scenarios.get(0).contains("'a;b'"));
    assertTrue(scenarios.get(1).startsWith("-- expect: SQL_SYNTAX"));
  }

  @Test
  void failsScenarioWhenExpectedErrorDoesNotOccur() {
    QueryOptimizer alwaysSucceeds =
        new QueryOptimizer() {
          @Override
          public PreparedQuery prepare(String sql) {
            throw new UnsupportedOperationException();
          }

          @Override
          public double estimateCost(PlanNode plan) {
            return 1;
          }

          @Override
          public PlanExplanation explain(String sql) {
            return new PlanExplanation("before", "after", 1);
          }
        };

    var report =
        new QueryOptimizerDriver(alwaysSucceeds)
            .runSuite(List.of("-- expect: SQL_SYNTAX\nSELECT * FROM student;"));

    assertFalse(report.passed());
    assertTrue(report.results().get(0).message().contains("expected SQL_SYNTAX"));
  }

  @Test
  void failsUnexpectedErrorWhenScenarioHasNoExpectationDirective() {
    QueryOptimizer alwaysFails =
        new QueryOptimizer() {
          @Override
          public PreparedQuery prepare(String sql) {
            throw new UnsupportedOperationException();
          }

          @Override
          public double estimateCost(PlanNode plan) {
            return 1;
          }

          @Override
          public PlanExplanation explain(String sql) {
            throw new DbmsException(ErrorCode.SQL_SYNTAX, "invalid SQL");
          }
        };

    var report = new QueryOptimizerDriver(alwaysFails).runSuite(List.of("SELEC * FROM student;"));

    assertFalse(report.passed());
    assertTrue(report.results().get(0).message().contains("unexpected SQL_SYNTAX"));
  }

  private final QueryOptimizer standalone = QueryOptimizerDriver.standaloneOptimizer();

  @Test
  void builtInSuitePassesAndCoversEveryScenarioFamily() throws IOException {
    List<String> scenarios = QueryOptimizerDriver.builtInScenarios();

    var report = new QueryOptimizerDriver(standalone).runSuite(scenarios);

    report.results().stream()
        .filter(result -> !result.passed())
        .forEach(result -> System.out.println(result.message()));
    assertTrue(report.passed());
    String all = String.join(System.lineSeparator(), scenarios);
    for (String family :
        List.of(
            "INSERT",
            "UPDATE",
            "DELETE",
            "BEGIN",
            "COMMIT",
            "ABORT",
            "CREATE INDEX",
            "NATURAL JOIN",
            "expect: SQL_SYNTAX",
            "expect: SQL_BIND",
            "IndexScan",
            "TableScan")) {
      assertTrue(all.contains(family), "built-in suite lacks " + family);
    }
  }

  @Test
  void failsScenarioWhenAfterPlanLacksExpectedText() {
    var report =
        new QueryOptimizerDriver(standalone)
            .runSuite(
                List.of(
                    """
                    -- expect: OK
                    -- expect-after: IndexScan
                    SELECT name FROM student WHERE student_id > 900;
                    """));

    assertFalse(report.passed());
    assertTrue(report.results().get(0).message().contains("AFTER plan lacks [IndexScan]"));
  }

  @Test
  void outputSchemaFollowsSelectOrderNotCatalogOrder() {
    PreparedQuery prepared =
        standalone.prepare("SELECT gpa, student.name, student_id FROM student");

    assertEquals(
        List.of("gpa", "name", "student_id"),
        prepared.outputSchema().columns().stream().map(Column::name).toList());
    ProjectPlan project = assertInstanceOf(ProjectPlan.class, prepared.root());
    assertEquals(
        List.of("gpa", "name", "student_id"),
        project.columns().stream().map(column -> column.column()).toList());
  }

  @Test
  void preparesDmlWithCanonicalPlansAndEmptySchema() {
    PreparedQuery insert = standalone.prepare("INSERT INTO student VALUES (1001, 'Alya', 3.9)");
    PreparedQuery update =
        standalone.prepare("UPDATE student SET gpa = gpa + 0.1 WHERE student_id = 1");
    PreparedQuery delete = standalone.prepare("DELETE FROM enrollment WHERE course_id = 3");

    assertEquals("student", assertInstanceOf(InsertPlan.class, insert.root()).table());
    UpdatePlan updatePlan = assertInstanceOf(UpdatePlan.class, update.root());
    assertEquals(
        "idx_student_id", assertInstanceOf(IndexScanPlan.class, updatePlan.source()).index());
    assertEquals("enrollment", assertInstanceOf(DeletePlan.class, delete.root()).table());
    for (PreparedQuery prepared : List.of(insert, update, delete)) {
      assertEquals(new Schema(List.of()), prepared.outputSchema());
    }
  }

  @Test
  void preparesTransactionAndCreateIndexWithoutOptimization() {
    for (TransactionCommand command : TransactionCommand.values()) {
      PreparedQuery prepared = standalone.prepare(command.name());
      assertEquals(
          command, assertInstanceOf(TransactionCommandPlan.class, prepared.root()).command());
      assertEquals(new Schema(List.of()), prepared.outputSchema());
    }

    PreparedQuery create =
        standalone.prepare("CREATE INDEX idx_course_credits ON course(credits) USING B_PLUS_TREE");

    assertEquals(
        new CreateIndexPlan("idx_course_credits", "course", "credits", IndexKind.B_PLUS_TREE),
        create.root());
    assertEquals(new Schema(List.of()), create.outputSchema());
  }

  @Test
  void reordersThreeTableJoinToStartFromFilteredSmallestRelation() {
    String sql =
        "SELECT student.name, course.title "
            + "FROM student, enrollment JOIN course ON enrollment.course_id = course.course_id "
            + "WHERE student.student_id = enrollment.student_id AND course.credits = 4 "
            + "ORDER BY student.name";

    PreparedQuery prepared = standalone.prepare(sql);

    SortPlan sort = assertInstanceOf(SortPlan.class, ((ProjectPlan) prepared.root()).child());
    JoinPlan top = assertInstanceOf(JoinPlan.class, sort.child());
    JoinPlan inner = assertInstanceOf(JoinPlan.class, top.left());
    assertEquals(JoinKind.THETA, top.kind());
    assertEquals(JoinKind.THETA, inner.kind());
    assertEquals("course", firstScan(inner.left()).table());
    assertEquals("student", firstScan(top.right()).table());
    assertTrue(standalone.explain(sql).before().contains("kind=CARTESIAN"));
  }

  private static TableScanPlan firstScan(PlanNode plan) {
    return switch (plan) {
      case TableScanPlan scan -> scan;
      case ProjectPlan project -> firstScan(project.child());
      case dynamitedb.contracts.query.FilterPlan filter -> firstScan(filter.child());
      case JoinPlan join -> firstScan(join.left());
      default -> throw new AssertionError("unexpected node " + plan);
    };
  }
}
