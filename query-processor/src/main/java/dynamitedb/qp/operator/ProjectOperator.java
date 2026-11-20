package dynamitedb.qp.operator;

import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.SelectItem;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ProjectOperator implements Operator {

  private final Operator child;
  private final List<SelectItem> items;
  private final ExpressionEvaluator evaluator;
  private Schema outputSchema;

  public ProjectOperator(Operator child, List<ColumnRef> columns) {
    this(
        child,
        columns.stream()
            .map(column -> new SelectItem(new ColumnExpression(column), Optional.empty()))
            .toList(),
        new ExpressionEvaluator());
  }

  public ProjectOperator(Operator child, List<SelectItem> items, ExpressionEvaluator evaluator) {
    this.child = Objects.requireNonNull(child, "child");
    this.items = List.copyOf(items);
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
  }

  @Override
  public void open() {
    child.open();
    outputSchema = null;
  }

  @Override
  public Optional<OperatorRow> next() {
    Optional<OperatorRow> nextRow = child.next();
    if (nextRow.isEmpty()) return Optional.empty();

    OperatorRow input = nextRow.get();
    if (outputSchema == null) outputSchema = schema(items, input.schema());
    List<Value> values =
        items.stream().map(item -> evaluator.evaluate(item.expression(), input)).toList();
    return Optional.of(new OperatorRow(new Row(values), outputSchema, input.origins()));
  }

  public static Schema schema(List<SelectItem> items, Schema input) {
    List<Column> columns = new ArrayList<>(items.size());
    for (int index = 0; index < items.size(); index++) {
      SelectItem item = items.get(index);
      Expression expression = item.expression();
      Column source = sourceColumn(expression, input);
      String defaultName =
          source != null
              ? source.name()
              : expression instanceof AggregateExpression aggregate
                  ? aggregate.outputName()
                  : "__select_item_" + index;
      String name = item.alias().orElse(defaultName);
      DataType type = source == null ? expressionType(expression, input) : source.dataType();
      columns.add(
          new Column(
              source == null || item.alias().isPresent() ? "" : source.relation(),
              name,
              type,
              source == null ? 0 : source.maxLength(),
              source == null || item.alias().isPresent() || source.nullable()));
    }
    return new Schema(columns);
  }

  private static Column sourceColumn(Expression expression, Schema input) {
    ColumnRef ref =
        switch (expression) {
          case ColumnExpression column -> column.column();
          case AggregateExpression aggregate ->
              new ColumnRef(Optional.empty(), aggregate.outputName());
          default -> null;
        };
    if (ref == null) return null;
    int found = -1;
    for (int index = 0; index < input.columns().size(); index++) {
      Column column = input.columns().get(index);
      if (column.name().equalsIgnoreCase(ref.column())
          && (ref.relation().isEmpty()
              || ref.relation().get().equalsIgnoreCase(column.relation()))) {
        if (found != -1) return null;
        found = index;
      }
    }
    return found < 0 ? null : input.columns().get(found);
  }

  private static DataType expressionType(Expression expression, Schema input) {
    return switch (expression) {
      case ColumnExpression column ->
          input.columns().get(findColumn(input, column.column())).dataType();
      case AggregateExpression aggregate ->
          aggregate.function() == dynamitedb.contracts.query.AggregateFunction.AVG
                  || aggregate.function() == dynamitedb.contracts.query.AggregateFunction.COUNT
              ? DataType.INTEGER
              : input
                  .columns()
                  .get(findColumn(input, aggregate.argument().orElseThrow()))
                  .dataType();
      case LiteralExpression literal ->
          switch (literal.value()) {
            case IntValue ignored -> DataType.INTEGER;
            case FloatValue ignored -> DataType.FLOAT;
            case TextValue ignored -> DataType.VARCHAR;
            default -> DataType.INTEGER;
          };
      case BinaryExpression binary ->
          expressionType(binary.left(), input) == DataType.FLOAT
                  || expressionType(binary.right(), input) == DataType.FLOAT
              ? DataType.FLOAT
              : DataType.INTEGER;
      default -> DataType.INTEGER;
    };
  }

  private static int findColumn(Schema input, ColumnRef ref) {
    for (int index = 0; index < input.columns().size(); index++) {
      Column column = input.columns().get(index);
      if (column.name().equalsIgnoreCase(ref.column())
          && (ref.relation().isEmpty() || ref.relation().get().equalsIgnoreCase(column.relation())))
        return index;
    }
    return 0;
  }

  @Override
  public void close() {
    child.close();
    outputSchema = null;
  }
}
