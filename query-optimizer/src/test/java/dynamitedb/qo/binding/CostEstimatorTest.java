package dynamitedb.qo.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.TextValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CostEstimatorTest {

  private static final PlanNode STUDENT = new TableScanPlan("student", "s");
  private static final PlanNode ENROLL = new TableScanPlan("enroll", "e");

  private final CostEstimator cost = new CostEstimator(new FakeCatalog());

  @Test
  void filtersScaleRowsBySelectivity() {
    assertEquals(1000, cost.estimateRows(STUDENT));
    assertEquals(100.0, cost.estimate(STUDENT));

    assertEquals(1, cost.estimateRows(filter(compare("s", "sid", BinaryOp.EQ, 5))));
    assertEquals(998, cost.estimateRows(filter(compare("s", "name", BinaryOp.NEQ, "x"))));
    assertEquals(333, cost.estimateRows(filter(compare("s", "gpa", BinaryOp.GT, 3.0))));
    assertEquals(
        7,
        cost.estimateRows(
            filter(
                new BinaryExpression(
                    compare("s", "gpa", BinaryOp.EQ, 3.5),
                    BinaryOp.AND,
                    compare("s", "gpa", BinaryOp.LT, 4.0)))));
    assertEquals(100.0, cost.estimate(filter(compare("s", "sid", BinaryOp.EQ, 5))));
  }

  @Test
  void joinsUseDistinctCountsForMatchingRows() {
    Expression sameStudent =
        new BinaryExpression(column("s", "sid"), BinaryOp.EQ, column("e", "sid"));

    assertEquals(5_000_000, cost.estimateRows(join(STUDENT, ENROLL, JoinKind.CARTESIAN, null)));
    assertEquals(5_000, cost.estimateRows(join(STUDENT, ENROLL, JoinKind.THETA, sameStudent)));
    assertEquals(5_000, cost.estimateRows(join(STUDENT, ENROLL, JoinKind.NATURAL, null)));
  }

  @Test
  void blockNestedLoopJoinFavorsTheSmallerOuterSide() {
    double studentOuter = cost.estimate(join(STUDENT, ENROLL, JoinKind.CARTESIAN, null));
    double enrollOuter = cost.estimate(join(ENROLL, STUDENT, JoinKind.CARTESIAN, null));

    assertEquals(100 + 10 * 250, studentOuter);
    assertEquals(250 + 50 * 100, enrollOuter);
    assertTrue(studentOuter < enrollOuter);
  }

  @Test
  void indexScanReadsOnlyMatchingRows() {
    var lookup =
        new IndexScanPlan(
            "idx_sid",
            "student",
            "s",
            new IndexPredicate(ComparisonOp.EQ, new IntValue(5)),
            Optional.empty());

    assertEquals(1, cost.estimateRows(lookup));
    assertEquals(2.0, cost.estimate(lookup));
    assertTrue(cost.estimate(lookup) < cost.estimate(filter(compare("s", "sid", BinaryOp.EQ, 5))));
  }

  @Test
  void indexResidualReducesOutputRowsButNotRowsFetchedFromStorage() {
    var lookup =
        new IndexScanPlan(
            "idx_sid",
            "student",
            "s",
            new IndexPredicate(ComparisonOp.EQ, new IntValue(5)),
            Optional.of(compare("s", "gpa", BinaryOp.GT, 3.0)));

    assertEquals(0, cost.estimateRows(lookup));
    assertEquals(2.0, cost.estimate(lookup));
  }

  @Test
  void projectionSortAndWritesFollowTheirSource() {
    var sorted =
        new ProjectPlan(
            new SortPlan(STUDENT, List.of(new SortKey(ref("s", "name"), SortDirection.ASC))),
            List.of(ref("s", "name")));
    assertEquals(1000, cost.estimateRows(sorted));
    assertEquals(100.0, cost.estimate(sorted));

    var delete =
        new DeletePlan(
            new FilterPlan(
                new TableScanPlan("student", "student"), compare("student", "sid", BinaryOp.EQ, 5)),
            "student");
    assertEquals(1, cost.estimateRows(delete));
    assertEquals(101.0, cost.estimate(delete));

    assertEquals(1.0, cost.estimate(new InsertPlan("student", new Row(List.of()))));
    assertEquals(
        100.0, cost.estimate(new CreateIndexPlan("idx_gpa", "student", "gpa", IndexKind.HASH)));
    assertEquals(0.0, cost.estimate(new TransactionCommandPlan(TransactionCommand.BEGIN)));
  }

  private static PlanNode filter(Expression predicate) {
    return new FilterPlan(STUDENT, predicate);
  }

  private static PlanNode join(PlanNode left, PlanNode right, JoinKind kind, Expression on) {
    return new JoinPlan(left, right, kind, Optional.ofNullable(on));
  }

  private static Expression compare(String alias, String column, BinaryOp op, Object value) {
    var literal =
        switch (value) {
          case Integer i -> new IntValue(i);
          case Double d -> new FloatValue(d);
          default -> new TextValue(value.toString());
        };
    return new BinaryExpression(column(alias, column), op, new LiteralExpression(literal));
  }

  private static ColumnExpression column(String alias, String column) {
    return new ColumnExpression(ref(alias, column));
  }

  private static ColumnRef ref(String alias, String column) {
    return new ColumnRef(Optional.of(alias), column);
  }

  private static final class FakeCatalog implements CatalogAccess {
    private final Map<String, Schema> schemas =
        Map.of(
            "student",
            new Schema(
                List.of(
                    new Column("student", "sid", DataType.INTEGER, 0, false),
                    new Column("student", "name", DataType.VARCHAR, 50, false),
                    new Column("student", "gpa", DataType.FLOAT, 0, true))),
            "enroll",
            new Schema(
                List.of(
                    new Column("enroll", "sid", DataType.INTEGER, 0, false),
                    new Column("enroll", "cid", DataType.INTEGER, 0, false))));

    private final Map<String, TableStatistics> statistics =
        Map.of(
            "student",
            new TableStatistics(1000, 100, 64, Map.of("sid", 1000L, "name", 500L, "gpa", 50L)),
            "enroll",
            new TableStatistics(5000, 250, 16, Map.of("sid", 1000L, "cid", 40L)));

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
      return statistics.get(table);
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return table.equals("student")
          ? List.of(new IndexMetadata("idx_sid", "student", "sid", IndexKind.HASH))
          : List.of();
    }
  }
}
