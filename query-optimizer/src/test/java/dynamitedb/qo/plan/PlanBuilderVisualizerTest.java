package dynamitedb.qo.plan;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.storage.BooleanValue;
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
}
