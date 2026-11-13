package dynamitedb.qo.pushdown;

import static dynamitedb.qo.pushdown.SelectionPushdownTest.C;
import static dynamitedb.qo.pushdown.SelectionPushdownTest.E;
import static dynamitedb.qo.pushdown.SelectionPushdownTest.S;
import static dynamitedb.qo.pushdown.SelectionPushdownTest.cartesian;
import static dynamitedb.qo.pushdown.SelectionPushdownTest.col;
import static dynamitedb.qo.pushdown.SelectionPushdownTest.compare;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectionPushdownTest {

  private final ProjectionPushdown pushdown = new ProjectionPushdown(new FakeCatalog());

  @Test
  void collapsesChainAndKeepsOnlyColumnsReadAboveEachTable() {
    Expression link = compare("s", "sid", BinaryOp.EQ, col("e", "sid"));
    Expression grade = compare("e", "grade", BinaryOp.EQ, 4);
    SortPlan sorted =
        new SortPlan(
            new FilterPlan(cartesian(S, new FilterPlan(E, grade)), link),
            List.of(new SortKey(ref("s", "gpa"), SortDirection.DESC)));
    PlanNode plan =
        new ProjectPlan(new ProjectPlan(sorted, refs("s.gpa", "s.name")), refs("s.name"));

    // student needs name (SELECT), gpa (ORDER BY) and sid (join) = all its columns, so no project.
    // enroll keeps sid for the join; grade is only read by the selection below the projection.
    PlanNode expected =
        new ProjectPlan(
            new SortPlan(
                new FilterPlan(
                    cartesian(S, new ProjectPlan(new FilterPlan(E, grade), refs("e.sid"))), link),
                sorted.keys()),
            refs("s.name"));
    PlanNode once = pushdown.rewrite(plan);
    assertEquals(expected, once);
    assertEquals(once, pushdown.rewrite(once));
  }

  @Test
  void keepsThetaConditionColumnsAndSelectOrder() {
    Expression sameCourse = compare("e", "cid", BinaryOp.EQ, col("c", "cid"));
    PlanNode plan =
        new ProjectPlan(
            new JoinPlan(E, C, JoinKind.THETA, Optional.of(sameCourse)), refs("c.title", "e.sid"));

    PlanNode expected =
        new ProjectPlan(
            new JoinPlan(
                new ProjectPlan(E, refs("e.sid", "e.cid")),
                C,
                JoinKind.THETA,
                Optional.of(sameCourse)),
            refs("c.title", "e.sid"));
    assertEquals(expected, pushdown.rewrite(plan));
  }

  @Test
  void keepsNaturalJoinSharedColumnsOnBothSides() {
    PlanNode plan =
        new ProjectPlan(new JoinPlan(S, E, JoinKind.NATURAL, Optional.empty()), refs("s.name"));

    PlanNode expected =
        new ProjectPlan(
            new JoinPlan(
                new ProjectPlan(S, refs("s.sid", "s.name")),
                new ProjectPlan(E, refs("e.sid")),
                JoinKind.NATURAL,
                Optional.empty()),
            refs("s.name"));
    assertEquals(expected, pushdown.rewrite(plan));
  }

  @Test
  void leavesSingleTableQueryUnchanged() {
    PlanNode plan =
        new ProjectPlan(new FilterPlan(S, compare("s", "gpa", BinaryOp.GT, 3)), refs("s.name"));

    assertEquals(plan, pushdown.rewrite(plan));
  }

  private static ColumnRef ref(String relation, String column) {
    return new ColumnRef(Optional.of(relation), column);
  }

  private static List<ColumnRef> refs(String... qualified) {
    return Arrays.stream(qualified).map(q -> ref(q.split("\\.")[0], q.split("\\.")[1])).toList();
  }

  private static final class FakeCatalog implements CatalogAccess {
    private final Map<String, Schema> schemas =
        Map.of(
            "student", schema("student", "sid", "name", "gpa"),
            "enroll", schema("enroll", "sid", "cid", "grade"),
            "course", schema("course", "cid", "title"));

    private static Schema schema(String table, String... columns) {
      return new Schema(
          Arrays.stream(columns)
              .map(c -> new Column(table, c, DataType.INTEGER, 0, false))
              .toList());
    }

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
      throw new UnsupportedOperationException();
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return List.of();
    }
  }
}
