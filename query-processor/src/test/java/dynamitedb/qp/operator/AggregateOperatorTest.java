package dynamitedb.qp.operator;

import static dynamitedb.qp.operator.TestRows.drain;
import static dynamitedb.qp.operator.TestRows.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregateFunction;
import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.SelectItem;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AggregateOperatorTest {

  private static final ColumnRef GPA = new ColumnRef(Optional.of("student"), "gpa");

  @Test
  void groupsRowsComputesAggregatesAndProjectsAnAlias() {
    Schema inputSchema =
        new Schema(
            List.of(
                new Column("student", "gpa", DataType.FLOAT, 0, false),
                new Column("student", "student_id", DataType.INTEGER, 0, false)));
    ListOperator input =
        table(inputSchema, new Object[] {3.5, 1}, new Object[] {3.5, 2}, new Object[] {4.0, 3});
    AggregateExpression count = new AggregateExpression(AggregateFunction.COUNT, Optional.empty());
    AggregateExpression average = new AggregateExpression(AggregateFunction.AVG, Optional.of(GPA));
    AggregateExpression sum = new AggregateExpression(AggregateFunction.SUM, Optional.of(GPA));
    AggregateExpression min = new AggregateExpression(AggregateFunction.MIN, Optional.of(GPA));
    AggregateExpression max = new AggregateExpression(AggregateFunction.MAX, Optional.of(GPA));
    AggregatePlan aggregate =
        new AggregatePlan(
            new TableScanPlan("student", "student"),
            List.of(GPA),
            List.of(count, average, sum, min, max));
    Expression having =
        new BinaryExpression(count, BinaryOp.GT, new LiteralExpression(new IntValue(1)));
    List<SelectItem> projection =
        List.of(
            new SelectItem(new ColumnExpression(GPA), Optional.empty()),
            new SelectItem(count, Optional.of("total")),
            new SelectItem(average, Optional.of("average_gpa")),
            new SelectItem(sum, Optional.of("sum_gpa")),
            new SelectItem(min, Optional.of("min_gpa")),
            new SelectItem(max, Optional.of("max_gpa")));

    List<dynamitedb.contracts.query.OperatorRow> rows =
        drain(
            new ProjectOperator(
                new FilterOperator(
                    new AggregateOperator(input, aggregate), having, new ExpressionEvaluator()),
                projection,
                new ExpressionEvaluator()));

    assertEquals(1, rows.size());
    assertEquals(
        List.of(
            new FloatValue(3.5),
            new IntValue(2),
            new FloatValue(3.5),
            new FloatValue(7.0),
            new FloatValue(3.5),
            new FloatValue(3.5)),
        rows.get(0).row().values());
    assertEquals("total", rows.get(0).schema().columns().get(1).name());
  }

  @Test
  void globalAggregateOnEmptyInputReturnsZeroCountAndNullSum() {
    AggregateExpression count = new AggregateExpression(AggregateFunction.COUNT, Optional.empty());
    AggregateExpression sum = new AggregateExpression(AggregateFunction.SUM, Optional.of(GPA));
    AggregateOperator operator =
        new AggregateOperator(
            new ListOperator(List.of()),
            new AggregatePlan(
                new TableScanPlan("student", "student"), List.of(), List.of(count, sum)));

    var rows = drain(operator);

    assertEquals(1, rows.size());
    assertEquals(new IntValue(0), rows.get(0).row().values().get(0));
    assertInstanceOf(NullValue.class, rows.get(0).row().values().get(1));
  }

  @Test
  void averageOfLargeIntegerValuesDoesNotOverflowAnIntegerSum() {
    Schema schema = new Schema(List.of(new Column("student", "sid", DataType.INTEGER, 0, false)));
    ColumnRef sid = new ColumnRef(Optional.of("student"), "sid");
    AggregateExpression average = new AggregateExpression(AggregateFunction.AVG, Optional.of(sid));
    AggregateOperator operator =
        new AggregateOperator(
            table(schema, new Object[] {2_000_000_000}, new Object[] {2_000_000_000}),
            new AggregatePlan(
                new TableScanPlan("student", "student"), List.of(), List.of(average)));

    var rows = drain(operator);

    assertEquals(new FloatValue(2_000_000_000.0), rows.get(0).row().values().get(0));
  }
}
