package dynamitedb.qo.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.CreateTablePlan;
import dynamitedb.contracts.query.CreateTableStatement;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.DropTablePlan;
import dynamitedb.contracts.query.DropTableStatement;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableDefinition;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanBuilderVisualizerTest {

  @Test
  void buildsCanonicalSelectPlanAndRendersIndentedTree() {
    var name = new ColumnRef(Optional.of("student"), "name");
    var statement =
        new SelectStatement(
            List.of(name),
            new JoinSource(
                new TableSource("student", "student"),
                new TableSource("enrollment", "enrollment"),
                JoinKind.NATURAL,
                Optional.empty()),
            Optional.of(new LiteralExpression(new BooleanValue(true))),
            List.of(new SortKey(name, SortDirection.ASC)));

    var plan = new PlanBuilder().build(statement);

    ProjectPlan project = assertInstanceOf(ProjectPlan.class, plan);
    SortPlan sort = assertInstanceOf(SortPlan.class, project.child());
    assertInstanceOf(FilterPlan.class, sort.child());
    String rendered = new PlanVisualizer().render(plan);
    assertTrue(rendered.startsWith("Project [columns=student.name]"));
    assertTrue(rendered.contains(System.lineSeparator() + "  Sort"));
    assertTrue(rendered.contains(System.lineSeparator() + "      Join [kind=NATURAL"));
  }

  @Test
  void placesAggregateAndHavingBelowProjectionAndSort() {
    var parser = new SqlParser(new StatementParser(), new ExpressionParser());
    var statement =
        (SelectStatement)
            parser.parse(
                new Lexer()
                    .tokenize(
                        "SELECT sid AS group_id, COUNT(*) AS total FROM student "
                            + "GROUP BY sid HAVING COUNT(*) > 1 ORDER BY total"));

    var sort = assertInstanceOf(SortPlan.class, new PlanBuilder().build(statement));
    var project = assertInstanceOf(ProjectPlan.class, sort.child());
    var having = assertInstanceOf(FilterPlan.class, project.child());
    var aggregate = assertInstanceOf(AggregatePlan.class, having.child());
    assertEquals(1, aggregate.groupBy().size());
    assertEquals(1, aggregate.aggregates().size());
    assertTrue(
        new PlanVisualizer().render(sort).contains("Aggregate [groupBy=sid, calls=COUNT(*)]"));
  }

  @Test
  void placesLimitAboveProjectionAndSortAndRendersIt() {
    var name = new ColumnRef(Optional.of("student"), "name");
    var statement =
        new SelectStatement(
            List.of(name),
            new TableSource("student", "student"),
            Optional.of(new LiteralExpression(new BooleanValue(true))),
            List.of(new SortKey(gpa, SortDirection.DESC)),
            Optional.of(0L));

    var plan = new PlanBuilder().build(statement);

    LimitPlan limit = assertInstanceOf(LimitPlan.class, plan);
    assertEquals(0, limit.count());
    ProjectPlan project = assertInstanceOf(ProjectPlan.class, limit.child());
    SortPlan sort = assertInstanceOf(SortPlan.class, project.child());
    assertInstanceOf(FilterPlan.class, sort.child());
    assertEquals(
        "Limit [count=0]"
            + NL
            + "  Project [columns=student.name]"
            + NL
            + "    Sort [keys=student.gpa DESC]"
            + NL
            + "      Filter [predicate=TRUE]"
            + NL
            + "        TableScan [table=student, alias=student]",
        visualizer.render(plan));
  }

  private static final String NL = System.lineSeparator();
  private final PlanVisualizer visualizer = new PlanVisualizer();
  private final ColumnRef gpa = new ColumnRef(Optional.of("student"), "gpa");

  @Test
  void rendersIndexScanWithAliasPredicateAndResidual() {
    var residual =
        new BinaryExpression(
            new ColumnExpression(gpa), BinaryOp.GT, new LiteralExpression(new FloatValue(3.0)));
    var scan =
        new IndexScanPlan(
            "idx_student_id",
            "student",
            "s",
            new IndexPredicate(ComparisonOp.EQ, new IntValue(7)),
            Optional.of(residual));

    assertEquals(
        "IndexScan [index=idx_student_id, table=student, alias=s, predicate=EQ 7, "
            + "residual=(student.gpa GT 3.0)]",
        visualizer.render(scan));
  }

  @Test
  void rendersIndexScanWithoutResidualAsNone() {
    var scan =
        new IndexScanPlan(
            "idx_name",
            "student",
            "student",
            new IndexPredicate(ComparisonOp.GTE, new TextValue("B")),
            Optional.empty());

    assertTrue(visualizer.render(scan).endsWith("predicate=GTE 'B', residual=none]"));
  }

  @Test
  void rendersInsertValuesAsSqlLiterals() {
    var insert =
        new InsertPlan(
            "student",
            new Row(List.of(new IntValue(1), new TextValue("O'Neil"), NullValue.INSTANCE)));

    assertEquals("Insert [table=student, values=(1, 'O''Neil', NULL)]", visualizer.render(insert));
  }

  @Test
  void rendersUpdateAboveItsSourcePlan() {
    var update =
        new UpdatePlan(
            new TableScanPlan("student", "student"),
            "student",
            gpa,
            new BinaryExpression(
                new ColumnExpression(gpa),
                BinaryOp.ADD,
                new LiteralExpression(new FloatValue(0.1))));

    assertEquals(
        "Update [table=student, target=student.gpa, value=(student.gpa ADD 0.1)]"
            + NL
            + "  TableScan [table=student, alias=student]",
        visualizer.render(update));
  }

  @Test
  void rendersDeleteAboveItsSourcePlan() {
    var delete = new DeletePlan(new TableScanPlan("enrollment", "e"), "enrollment");

    assertEquals(
        "Delete [table=enrollment]" + NL + "  TableScan [table=enrollment, alias=e]",
        visualizer.render(delete));
  }

  @Test
  void rendersCreateIndexWithKind() {
    var create = new CreateIndexPlan("idx_gpa", "student", "gpa", IndexKind.B_PLUS_TREE);

    assertEquals(
        "CreateIndex [index=idx_gpa, table=student, column=gpa, kind=B_PLUS_TREE]",
        visualizer.render(create));
  }

  @Test
  void buildsAndRendersCreateTable() {
    var columns =
        List.of(
            new Column("grade", "id", DataType.INTEGER, 0, false),
            new Column("grade", "sid", DataType.INTEGER, 0, true),
            new Column("grade", "mark", DataType.CHAR, 2, true));
    var primaryKey = new PrimaryKey(List.of("id"));
    var keys =
        List.of(
            new ForeignKey("grade", List.of("sid"), "student", List.of("student_id")),
            new ForeignKey("grade", List.of("id", "sid"), "pair", List.of("a", "b")));

    var plan =
        new PlanBuilder()
            .build(new CreateTableStatement("grade", columns, List.of(primaryKey), keys));

    assertEquals(
        new CreateTablePlan(
            new TableDefinition("grade", new Schema(columns), Optional.of(primaryKey), keys)),
        plan);
    assertEquals(
        "CreateTable [table=grade, columns=(id INTEGER NOT NULL, sid INTEGER, mark CHAR(2)),"
            + " primaryKey=(id), foreignKeys=(sid -> student(student_id); id, sid -> pair(a, b))]",
        visualizer.render(plan));
    assertEquals(
        "CreateTable [table=t, columns=(v VARCHAR(9)), primaryKey=none, foreignKeys=none]",
        visualizer.render(
            new CreateTablePlan(
                new TableDefinition(
                    "t",
                    new Schema(List.of(new Column("t", "v", DataType.VARCHAR, 9, true))),
                    Optional.empty(),
                    List.of()))));
  }

  @Test
  void buildsAndRendersDropTable() {
    var plan = new PlanBuilder().build(new DropTableStatement("grade", DropBehavior.CASCADE));

    assertEquals(new DropTablePlan("grade", DropBehavior.CASCADE), plan);
    assertEquals("DropTable [table=grade, behavior=CASCADE]", visualizer.render(plan));
  }

  @Test
  void rendersEveryTransactionCommand() {
    for (TransactionCommand command : TransactionCommand.values()) {
      assertEquals(
          "Transaction [command=" + command + "]",
          visualizer.render(new TransactionCommandPlan(command)));
    }
  }
}
