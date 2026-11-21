package dynamitedb.qp.operator;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregateFunction;
import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AggregateOperator implements Operator {

  private final Operator child;
  private final AggregatePlan plan;
  private List<OperatorRow> output;
  private int position;
  private boolean isOpen;

  public AggregateOperator(Operator child, AggregatePlan plan) {
    this.child = Objects.requireNonNull(child, "child");
    this.plan = Objects.requireNonNull(plan, "plan");
  }

  @Override
  public void open() {
    child.open();
    output = null;
    position = 0;
    isOpen = true;
  }

  @Override
  public Optional<OperatorRow> next() {
    if (!isOpen) throw new DbmsException(ErrorCode.INVALID_STATE, "AggregateOperator must be open");
    if (output == null) materialize();
    return position < output.size() ? Optional.of(output.get(position++)) : Optional.empty();
  }

  @Override
  public void close() {
    child.close();
    output = null;
    isOpen = false;
  }

  private void materialize() {
    Map<List<Value>, Group> groups = new LinkedHashMap<>();
    Schema inputSchema = null;
    for (Optional<OperatorRow> next = child.next(); next.isPresent(); next = child.next()) {
      OperatorRow row = next.get();
      if (inputSchema == null) inputSchema = row.schema();
      List<Value> key = plan.groupBy().stream().map(row::getValue).toList();
      Group group = groups.computeIfAbsent(key, ignored -> new Group(key, plan.aggregates()));
      group.add(row);
    }

    if (plan.groupBy().isEmpty() && groups.isEmpty()) {
      groups.put(List.of(), new Group(List.of(), plan.aggregates()));
    }

    Schema schema = outputSchema(inputSchema, plan);
    output = groups.values().stream().map(group -> toRow(group, schema)).toList();
  }

  private static OperatorRow toRow(Group group, Schema schema) {
    List<Value> values = new ArrayList<>(group.key());
    for (Accumulator accumulator : group.accumulators()) values.add(accumulator.result());
    return new OperatorRow(new Row(values), schema, Map.of());
  }

  private static Schema outputSchema(Schema input, AggregatePlan plan) {
    List<Column> columns = new ArrayList<>();
    if (input != null) {
      for (ColumnRef key : plan.groupBy()) {
        int index = findColumn(input, key);
        columns.add(input.columns().get(index));
      }
    }
    for (AggregateExpression aggregate : plan.aggregates()) {
      Column source =
          input == null || aggregate.argument().isEmpty()
              ? null
              : input.columns().get(findColumn(input, aggregate.argument().orElseThrow()));
      DataType type =
          switch (aggregate.function()) {
            case COUNT -> DataType.INTEGER;
            case AVG -> DataType.FLOAT;
            case SUM, MIN, MAX -> source == null ? DataType.FLOAT : source.dataType();
          };
      columns.add(
          new Column(
              "",
              aggregate.outputName(),
              type,
              source == null ? 0 : source.maxLength(),
              aggregate.function() != AggregateFunction.COUNT));
    }
    return new Schema(columns);
  }

  private static int findColumn(Schema schema, ColumnRef ref) {
    int found = -1;
    for (int index = 0; index < schema.columns().size(); index++) {
      Column column = schema.columns().get(index);
      if (column.name().equalsIgnoreCase(ref.column())
          && (ref.relation().isEmpty()
              || (column.relation() != null
                  && column.relation().equalsIgnoreCase(ref.relation().get())))) {
        if (found != -1) {
          throw new DbmsException(ErrorCode.SQL_BIND, "Ambiguous aggregate column: " + ref);
        }
        found = index;
      }
    }
    if (found == -1)
      throw new DbmsException(ErrorCode.SQL_BIND, "Aggregate column not found: " + ref);
    return found;
  }

  private static final class Group {
    private final List<Value> key;
    private final List<Accumulator> accumulators;

    private Group(List<Value> key, List<AggregateExpression> expressions) {
      this.key = List.copyOf(key);
      this.accumulators = expressions.stream().map(Accumulator::new).toList();
    }

    private List<Value> key() {
      return key;
    }

    private List<Accumulator> accumulators() {
      return accumulators;
    }

    private void add(OperatorRow row) {
      for (Accumulator accumulator : accumulators) accumulator.add(row);
    }
  }

  private static final class Accumulator {
    private final AggregateExpression expression;
    private long count;
    private double sum;
    private long integerSum;
    private boolean floating;
    private Value extremum;

    private Accumulator(AggregateExpression expression) {
      this.expression = expression;
    }

    private void add(OperatorRow row) {
      if (expression.argument().isEmpty()) {
        incrementCount();
        return;
      }
      Value value = row.getValue(expression.argument().orElseThrow());
      if (value instanceof NullValue) return;
      if (expression.function() == AggregateFunction.COUNT) {
        incrementCount();
      } else if (expression.function() == AggregateFunction.SUM
          || expression.function() == AggregateFunction.AVG) {
        if (expression.function() == AggregateFunction.AVG) {
          sum += numeric(value);
          floating = true;
          incrementCount();
        } else {
          addNumber(value);
          incrementCount();
        }
      } else if (extremum == null
          || (expression.function() == AggregateFunction.MIN
              ? ExpressionEvaluator.compare(value, extremum) < 0
              : ExpressionEvaluator.compare(value, extremum) > 0)) {
        extremum = value;
      }
    }

    private void incrementCount() {
      try {
        count = Math.incrementExact(count);
      } catch (ArithmeticException exception) {
        throw new DbmsException(ErrorCode.INVALID_STATE, "Aggregate count overflow");
      }
    }

    private void addNumber(Value value) {
      if (value instanceof IntValue integer && !floating) {
        try {
          integerSum = Math.addExact(integerSum, integer.value());
        } catch (ArithmeticException exception) {
          throw new DbmsException(ErrorCode.INVALID_STATE, "Integer SUM overflow");
        }
        sum = integerSum;
      } else if (value instanceof IntValue integer) {
        sum += integer.value();
      } else if (value instanceof FloatValue decimal) {
        floating = true;
        sum = integerSum + (sum - integerSum) + decimal.value();
      } else {
        throw new DbmsException(ErrorCode.SQL_BIND, "SUM and AVG require numeric values");
      }
    }

    private static double numeric(Value value) {
      return switch (value) {
        case IntValue integer -> integer.value();
        case FloatValue decimal -> decimal.value();
        default ->
            throw new DbmsException(ErrorCode.SQL_BIND, "SUM and AVG require numeric values");
      };
    }

    private Value result() {
      return switch (expression.function()) {
        case COUNT -> countResult();
        case SUM ->
            count == 0 ? NullValue.INSTANCE : floating ? new FloatValue(sum) : integerSumResult();
        case AVG -> count == 0 ? NullValue.INSTANCE : new FloatValue(sum / count);
        case MIN, MAX -> extremum == null ? NullValue.INSTANCE : extremum;
      };
    }

    private IntValue integerSumResult() {
      try {
        return new IntValue(Math.toIntExact(integerSum));
      } catch (ArithmeticException exception) {
        throw new DbmsException(ErrorCode.INVALID_STATE, "Integer SUM overflow");
      }
    }

    private IntValue countResult() {
      try {
        return new IntValue(Math.toIntExact(count));
      } catch (ArithmeticException exception) {
        throw new DbmsException(ErrorCode.INVALID_STATE, "Aggregate count exceeds INTEGER range");
      }
    }
  }
}
