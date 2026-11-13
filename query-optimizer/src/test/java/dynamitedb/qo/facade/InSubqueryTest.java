package dynamitedb.qo.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.join.AccessPathOptimizer;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.JoinOrderOptimizer;
import dynamitedb.qo.join.JoinRewriter;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import dynamitedb.qo.pipeline.PlanRewrite;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.plan.PlanVisualizer;
import dynamitedb.qo.pushdown.ProjectionPushdown;
import dynamitedb.qo.pushdown.SelectionPushdown;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Subquery IN / NOT IN di WHERE dari parse, bind, plan, sampai optimasi dan EXPLAIN. */
class InSubqueryTest {

  private static final String SELECT = "SELECT sid FROM student WHERE ";
  private static final String NL = System.lineSeparator();

  private final CatalogAccess catalog = new FakeCatalog();
  private final QueryOptimizerImpl optimizer = optimizer(catalog);

  // ---- parser ----

  @Test
  void inBindsTighterThanAndOnEitherSide() {
    for (String where :
        List.of(
            "gpa = 1 AND sid IN (SELECT sid FROM enroll)",
            "sid IN (SELECT sid FROM enroll) AND gpa = 1")) {
      var and = assertInstanceOf(BinaryExpression.class, where(where));
      assertEquals(BinaryOp.AND, and.operator());
      boolean inFirst = and.left() instanceof InSubqueryExpression;
      var in = assertInstanceOf(InSubqueryExpression.class, inFirst ? and.left() : and.right());
      var eq = assertInstanceOf(BinaryExpression.class, inFirst ? and.right() : and.left());
      assertEquals(BinaryOp.EQ, eq.operator());
      assertEquals(column(Optional.empty(), "sid"), in.operand());
      assertFalse(in.negated());
    }
  }

  @Test
  void arithmeticOperandIsParsedBeforeIn() {
    var in =
        assertInstanceOf(
            InSubqueryExpression.class, where("sid + 1 NOT IN (SELECT sid FROM enroll)"));
    assertTrue(in.negated());
    assertEquals(BinaryOp.ADD, assertInstanceOf(BinaryExpression.class, in.operand()).operator());
  }

  @Test
  void parsesSubqueryWithWhereOrderByAndNesting() {
    var in =
        assertInstanceOf(
            InSubqueryExpression.class,
            where(
                "sid IN (SELECT sid FROM enroll WHERE cid IN (SELECT sid FROM student)"
                    + " ORDER BY sid DESC)"));
    assertTrue(in.plan().isEmpty());
    assertEquals(new TableSource("enroll", "enroll"), in.query().from());
    assertEquals(SortDirection.DESC, in.query().orderBy().get(0).direction());
    var nested = assertInstanceOf(InSubqueryExpression.class, in.query().predicate().orElseThrow());
    assertEquals(new TableSource("student", "student"), nested.query().from());
  }

  @Test
  void keywordsAreCaseInsensitiveAndWhitespaceTolerant() {
    var lower =
        assertInstanceOf(InSubqueryExpression.class, where("sid not in(select sid from enroll)"));
    assertTrue(lower.negated());
    var mixed =
        assertInstanceOf(
            InSubqueryExpression.class, where("sid\n  In\t(  Select sid From enroll  )"));
    assertFalse(mixed.negated());
  }

  @Test
  void rejectsMalformedAndUnsupportedFormsWithSyntaxErrors() {
    assertError(ErrorCode.SQL_SYNTAX, "Unclosed parenthesis", "sid IN (SELECT sid FROM enroll");
    assertError(ErrorCode.SQL_SYNTAX, "IN only supports a subquery", "sid IN ()");
    assertError(ErrorCode.SQL_SYNTAX, "IN only supports a subquery", "sid IN (1, 2)");
    assertError(ErrorCode.SQL_SYNTAX, "Expected '(' after IN", "sid IN SELECT sid FROM enroll");
    assertError(ErrorCode.SQL_SYNTAX, "Expected '(' after IN", "sid IN");
    assertError(ErrorCode.SQL_SYNTAX, "Expected IN after NOT", "sid NOT 1");
    assertError(ErrorCode.SQL_SYNTAX, "Expected IN after NOT", "sid NOT");
    assertError(ErrorCode.SQL_SYNTAX, "Expected expression", "NOT sid IN (SELECT sid FROM enroll)");
    assertError(ErrorCode.SQL_SYNTAX, "Unexpected token", "sid IN (SELECT sid FROM enroll) gpa");
    assertError(
        ErrorCode.SQL_SYNTAX, "Unexpected token", "sid IN (SELECT sid FROM enroll) (SELECT 1)");
    assertError(ErrorCode.SQL_SYNTAX, "';' inside subquery", "sid IN (SELECT sid FROM enroll;)");
    assertError(ErrorCode.SQL_SYNTAX, "Expected FROM", "sid IN (SELECT sid)");
    assertError(ErrorCode.SQL_SYNTAX, "Scalar subqueries", "sid = (SELECT sid FROM enroll)");
    assertError(ErrorCode.SQL_SYNTAX, "EXISTS subqueries", "EXISTS (SELECT sid FROM enroll)");
  }

  // ---- binder ----

  @Test
  void rejectsCorrelatedReferencesQualifiedOrNot() {
    assertError(
        ErrorCode.SQL_BIND,
        "Unknown column: student.gpa (correlated subqueries are not supported)",
        "sid IN (SELECT sid FROM enroll WHERE cid = student.gpa)");
    assertError(
        ErrorCode.SQL_BIND,
        "Unknown column: gpa (correlated subqueries are not supported)",
        "sid IN (SELECT sid FROM enroll WHERE cid = gpa)");
  }

  @Test
  void subqueryMustProduceExactlyOneColumn() {
    assertError(
        ErrorCode.SQL_BIND, "exactly one column, got 2", "sid IN (SELECT sid, cid FROM enroll)");
    assertError(ErrorCode.SQL_BIND, "exactly one column, got 2", "sid IN (SELECT * FROM enroll)");
    // SELECT * yang mengembang jadi satu kolom tetap diterima
    optimizer.prepare(SELECT + "sid IN (SELECT * FROM single)");
  }

  @Test
  void checksOperandAgainstSubqueryColumnType() {
    assertError(
        ErrorCode.SQL_BIND,
        "IN cannot compare text with a number subquery",
        "name IN (SELECT sid FROM enroll)");
    assertError(
        ErrorCode.SQL_BIND,
        "IN cannot compare number with a text subquery",
        "sid IN (SELECT name FROM student)");
    assertError(ErrorCode.SQL_BIND, "IN cannot compare", "(sid = 1) IN (SELECT sid FROM enroll)");
    // INTEGER dan FLOAT sama-sama number
    optimizer.prepare(SELECT + "gpa IN (SELECT sid FROM enroll)");
    optimizer.prepare(SELECT + "sid NOT IN (SELECT gpa FROM student)");
  }

  @Test
  void reportsUnknownTableOrColumnInsideSubquery() {
    assertError(ErrorCode.SQL_BIND, "Unknown table: nowhere", "sid IN (SELECT sid FROM nowhere)");
    assertError(
        ErrorCode.SQL_BIND, "Unknown column: missing", "sid IN (SELECT missing FROM enroll)");
  }

  @Test
  void subqueryAliasShadowsOuterAlias() {
    var plan =
        optimizer
            .prepare(
                "SELECT s.name FROM student AS s WHERE s.sid IN"
                    + " (SELECT s.cid FROM enroll AS s WHERE s.sid = 1)")
            .root();

    var in = assertInstanceOf(InSubqueryExpression.class, find(plan, FilterPlan.class).predicate());
    assertEquals(column(Optional.of("s"), "sid"), in.operand());
    assertEquals(List.of(new ColumnRef(Optional.of("s"), "cid")), in.query().projection());
    assertEquals(new TableSource("enroll", "s"), in.query().from());
    var subplan = assertInstanceOf(ProjectPlan.class, in.plan().orElseThrow());
    assertEquals("enroll", find(subplan, TableScanPlan.class).table());
  }

  @Test
  void inIsAllowedInUpdateAndDeleteWhere() {
    String update = "UPDATE student SET gpa = 4.0 WHERE sid IN (SELECT sid FROM enroll)";
    String delete = "DELETE FROM student WHERE sid NOT IN (SELECT sid FROM enroll)";
    assertTrue(optimizer.explain(update).after().contains("(student.sid IN subquery)"));
    assertTrue(optimizer.explain(delete).after().contains("(student.sid NOT IN subquery)"));
  }

  // ---- plan dan optimasi ----

  @Test
  void pushesInPredicateBelowJoinToItsOwnSide() {
    String sql =
        "SELECT s.name FROM student AS s, enroll AS e WHERE s.sid = e.sid"
            + " AND s.sid IN (SELECT sid FROM enroll WHERE cid = 7) ORDER BY s.name";

    var join = find(optimizer.prepare(sql).root(), JoinPlan.class);

    var pushed = find(join, FilterPlan.class);
    assertInstanceOf(InSubqueryExpression.class, pushed.predicate());
    assertEquals("s", find(pushed.child(), TableScanPlan.class).alias());
  }

  @Test
  void inIsNotAnIndexAccessPathButRidesAlongAsResidual() {
    // sid punya index, tapi IN tidak dipakai sebagai akses index
    var alone = optimizer.prepare(SELECT + "sid IN (SELECT sid FROM enroll)").root();
    assertInstanceOf(InSubqueryExpression.class, find(alone, FilterPlan.class).predicate());
    assertInstanceOf(TableScanPlan.class, find(alone, FilterPlan.class).child());

    String sql = SELECT + "sid = 5 AND sid IN (SELECT sid FROM enroll)";
    var scan = find(optimizer.prepare(sql).root(), IndexScanPlan.class);
    assertInstanceOf(InSubqueryExpression.class, scan.residual().orElseThrow());
    String after = optimizer.explain(sql).after();
    assertTrue(after.contains("residual=(student.sid IN subquery)"), after);
    assertTrue(after.contains(NL + "    Subquery" + NL), after);
  }

  @Test
  void costEstimationHandlesInPredicates() {
    var plan =
        optimizer
            .prepare(
                SELECT + "sid IN (SELECT sid FROM enroll) AND sid NOT IN (SELECT cid FROM enroll)")
            .root();
    double cost = new CostEstimator(catalog).estimate(plan);
    assertTrue(cost > 0 && Double.isFinite(cost), "cost=" + cost);
    assertTrue(optimizer.explain(SELECT + "sid IN (SELECT sid FROM enroll)").cost() > 0);
  }

  @Test
  void explainRendersNestedSubqueriesAsIndentedSubtrees() {
    String after =
        optimizer
            .explain(
                SELECT
                    + "sid IN (SELECT sid FROM enroll WHERE cid NOT IN (SELECT sid FROM single))")
            .after();

    String expected =
        String.join(
            NL,
            "Project [columns=student.sid]",
            "  Filter [predicate=(student.sid IN subquery)]",
            "    Subquery",
            "      Project [columns=enroll.sid]",
            "        Filter [predicate=(enroll.cid NOT IN subquery)]",
            "          Subquery",
            "            Project [columns=single.sid]",
            "              TableScan [table=single, alias=single]",
            "          TableScan [table=enroll, alias=enroll]",
            "    TableScan [table=student, alias=student]");
    assertEquals(expected, after);
  }

  private Expression where(String condition) {
    Statement statement =
        new SqlParser(new StatementParser(), new ExpressionParser())
            .parse(new Lexer().tokenize(SELECT + condition));
    return ((SelectStatement) statement).predicate().orElseThrow();
  }

  private void assertError(ErrorCode code, String message, String condition) {
    var error =
        assertThrows(DbmsException.class, () -> optimizer.prepare(SELECT + condition), condition);
    assertEquals(code, error.getCode(), error.getMessage());
    assertTrue(error.getMessage().contains(message), error.getMessage());
  }

  private static ColumnExpression column(Optional<String> relation, String name) {
    return new ColumnExpression(new ColumnRef(relation, name));
  }

  private static <T extends PlanNode> T find(PlanNode plan, Class<T> type) {
    if (type.isInstance(plan)) return type.cast(plan);
    return switch (plan) {
      case ProjectPlan project -> find(project.child(), type);
      case SortPlan sort -> find(sort.child(), type);
      case FilterPlan filter -> find(filter.child(), type);
      case JoinPlan join -> {
        try {
          yield find(join.left(), type);
        } catch (AssertionError e) {
          yield find(join.right(), type);
        }
      }
      default -> throw new AssertionError("No " + type.getSimpleName() + " in " + plan);
    };
  }

  private static QueryOptimizerImpl optimizer(CatalogAccess catalog) {
    CostEstimator cost = new CostEstimator(catalog);
    return new QueryOptimizerImpl(
        new Lexer(),
        new SqlParser(new StatementParser(), new ExpressionParser()),
        new Binder(catalog),
        new PlanBuilder(),
        new OptimizerPipeline(
            List.<PlanRewrite>of(
                new SelectionPushdown(),
                new ProjectionPushdown(catalog),
                new JoinRewriter(),
                new AccessPathOptimizer(catalog)),
            new JoinOrderOptimizer(catalog),
            new BestPlanSelector(cost)),
        cost,
        new PlanVisualizer());
  }

  private static final class FakeCatalog implements CatalogAccess {
    private final Map<String, Schema> schemas =
        Map.of(
            "student",
            new Schema(
                List.of(
                    new Column("student", "sid", DataType.INTEGER, 0, false),
                    new Column("student", "name", DataType.VARCHAR, 5, false),
                    new Column("student", "gpa", DataType.FLOAT, 0, true))),
            "enroll",
            new Schema(
                List.of(
                    new Column("enroll", "sid", DataType.INTEGER, 0, false),
                    new Column("enroll", "cid", DataType.INTEGER, 0, false))),
            "single",
            new Schema(List.of(new Column("single", "sid", DataType.INTEGER, 0, false))));

    @Override
    public List<String> listTables() {
      return List.copyOf(schemas.keySet());
    }

    @Override
    public long getTableId(String table) {
      return 1;
    }

    @Override
    public Schema getSchema(String table) {
      return schemas.get(table);
    }

    @Override
    public TableStatistics getStatistics(String table) {
      return new TableStatistics(100, 10, 20, Map.of("sid", 50L, "cid", 10L));
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return table.equals("student")
          ? List.of(new IndexMetadata("idx_student_sid", "student", "sid", IndexKind.HASH))
          : List.of();
    }
  }
}
