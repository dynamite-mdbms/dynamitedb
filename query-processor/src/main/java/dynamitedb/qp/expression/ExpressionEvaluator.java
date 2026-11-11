package dynamitedb.qp.expression;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/** Ngitung nilai ekspresi atau kondisi buat satu baris (QP-04). */
public class ExpressionEvaluator {

  // null berarti evaluator ini ga bisa jalanin subquery (dibikin per query sama PlanTranslator)
  private final Function<PlanNode, List<Value>> subqueries;
  // subquery tak berkorelasi cukup dijalanin sekali, hasilnya dipake buat semua baris
  private final Map<InSubqueryExpression, TreeSet<Value>> materialized = new IdentityHashMap<>();

  public ExpressionEvaluator() {
    this(null);
  }

  public ExpressionEvaluator(Function<PlanNode, List<Value>> subqueries) {
    this.subqueries = subqueries;
  }

  public Value evaluate(Expression expr, OperatorRow row) {
    return switch (expr) {
      case LiteralExpression literal -> literal.value();
      case ColumnExpression column -> row.getValue(column.column());
      case BinaryExpression binary -> evaluateBinary(binary, row);
      case InSubqueryExpression in -> evaluateIn(in, row);
    };
  }

  public boolean evaluatePredicate(Expression expr, OperatorRow row) {
    // NULL sama FALSE sama-sama ga lolos
    return evaluate(expr, row) instanceof BooleanValue result && result.value();
  }

  private Value evaluateBinary(BinaryExpression expr, OperatorRow row) {
    Value left = evaluate(expr.left(), row);
    Value right = evaluate(expr.right(), row);
    if (expr.operator() == BinaryOp.AND) {
      return and(left, right);
    }
    if (left instanceof NullValue || right instanceof NullValue) {
      return NullValue.INSTANCE;
    }
    return switch (expr.operator()) {
      case ADD, SUB, MUL, DIV -> arithmetic(expr.operator(), left, right);
      default -> new BooleanValue(holds(expr.operator(), compare(left, right)));
    };
  }

  // logika tiga nilai SQL: ga ketemu tapi subquery ada NULL hasilnya NULL
  private Value evaluateIn(InSubqueryExpression expr, OperatorRow row) {
    TreeSet<Value> values = materialized.computeIfAbsent(expr, this::materialize);
    if (values.isEmpty()) return new BooleanValue(expr.negated());
    Value operand = evaluate(expr.operand(), row);
    if (operand instanceof NullValue) return NullValue.INSTANCE;
    if (values.contains(operand)) return new BooleanValue(!expr.negated());
    return values.contains(NullValue.INSTANCE)
        ? NullValue.INSTANCE
        : new BooleanValue(expr.negated());
  }

  private TreeSet<Value> materialize(InSubqueryExpression expr) {
    if (subqueries == null || expr.plan().isEmpty()) {
      throw new DbmsException(ErrorCode.INVALID_PLAN, "IN subquery has no executable plan");
    }
    // compare() dipake supaya 1 = 1.0 dan padding CHAR tetap dianggap sama
    TreeSet<Value> values = new TreeSet<>(ExpressionEvaluator::compareNullsFirst);
    values.addAll(subqueries.apply(expr.plan().get()));
    return values;
  }

  private static int compareNullsFirst(Value left, Value right) {
    boolean leftNull = left instanceof NullValue;
    boolean rightNull = right instanceof NullValue;
    if (leftNull || rightNull) return Boolean.compare(!leftNull, !rightNull);
    return compare(left, right);
  }

  // AND tiga nilai: ada FALSE hasilnya FALSE, ada NULL hasilnya NULL, sisanya TRUE
  private static Value and(Value left, Value right) {
    Optional<Boolean> l = truth(left);
    Optional<Boolean> r = truth(right);
    if (l.equals(Optional.of(false)) || r.equals(Optional.of(false))) {
      return new BooleanValue(false);
    }
    if (l.isEmpty() || r.isEmpty()) {
      return NullValue.INSTANCE;
    }
    return new BooleanValue(true);
  }

  private static Optional<Boolean> truth(Value value) {
    return switch (value) {
      case BooleanValue bool -> Optional.of(bool.value());
      case NullValue ignored -> Optional.empty();
      default -> throw typeError("AND needs boolean operands", value);
    };
  }

  // INT op INT tetap INT, kalau ada FLOAT naik ke FLOAT
  private static Value arithmetic(BinaryOp op, Value left, Value right) {
    if (left instanceof IntValue a && right instanceof IntValue b) {
      return new IntValue(
          switch (op) {
            case ADD -> a.value() + b.value();
            case SUB -> a.value() - b.value();
            case MUL -> a.value() * b.value();
            default -> a.value() / nonZero(b.value());
          });
    }
    double a = number(left);
    double b = number(right);
    return new FloatValue(
        switch (op) {
          case ADD -> a + b;
          case SUB -> a - b;
          case MUL -> a * b;
          default -> a / nonZero(b);
        });
  }

  public static int compare(Value left, Value right) {
    if (left instanceof TextValue a && right instanceof TextValue b) {
      // CHAR disimpen dengan padding, jadi spasi di belakang ga dihitung
      return a.value().stripTrailing().compareTo(b.value().stripTrailing());
    }
    if (left instanceof BooleanValue a && right instanceof BooleanValue b) {
      return Boolean.compare(a.value(), b.value());
    }
    return Double.compare(number(left), number(right));
  }

  private static boolean holds(BinaryOp op, int cmp) {
    return switch (op) {
      case EQ -> cmp == 0;
      case NEQ -> cmp != 0;
      case LT -> cmp < 0;
      case LTE -> cmp <= 0;
      case GT -> cmp > 0;
      default -> cmp >= 0;
    };
  }

  private static double number(Value value) {
    return switch (value) {
      case IntValue v -> v.value();
      case FloatValue v -> v.value();
      default -> throw typeError("Expected a number", value);
    };
  }

  private static int nonZero(int value) {
    if (value == 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Division by zero");
    }
    return value;
  }

  private static double nonZero(double value) {
    if (value == 0.0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Division by zero");
    }
    return value;
  }

  private static DbmsException typeError(String message, Value value) {
    return new DbmsException(ErrorCode.SQL_BIND, message + ", got " + value);
  }
}
