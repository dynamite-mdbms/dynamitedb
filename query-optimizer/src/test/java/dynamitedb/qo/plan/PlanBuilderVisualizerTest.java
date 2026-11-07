package dynamitedb.qo.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
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
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.TextValue;
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
  void rendersEveryTransactionCommand() {
    for (TransactionCommand command : TransactionCommand.values()) {
      assertEquals(
          "Transaction [command=" + command + "]",
          visualizer.render(new TransactionCommandPlan(command)));
    }
  }
}
