package dynamitedb.qo.join;

import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.LiteralExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PlanExpressions {

  private PlanExpressions() {}

  static List<Expression> conjuncts(Expression expression) {
    List<Expression> result = new ArrayList<>();
    if (expression instanceof BinaryExpression binary && binary.operator() == BinaryOp.AND) {
      result.addAll(conjuncts(binary.left()));
      result.addAll(conjuncts(binary.right()));
    } else {
      result.add(expression);
    }
    return result;
  }

  static Expression and(List<Expression> expressions) {
    if (expressions.isEmpty()) throw new IllegalArgumentException("expression list is empty");
    Expression combined = expressions.get(0);
    for (int i = 1; i < expressions.size(); i++) {
      combined = new BinaryExpression(combined, BinaryOp.AND, expressions.get(i));
    }
    return combined;
  }

  static Optional<Expression> combine(Optional<Expression> existing, Expression incoming) {
    List<Expression> all = new ArrayList<>();
    existing.ifPresent(expression -> all.addAll(conjuncts(expression)));
    all.addAll(conjuncts(incoming));
    return all.isEmpty() ? Optional.empty() : Optional.of(and(all));
  }

  static List<ColumnRef> columns(Expression expression) {
    List<ColumnRef> result = new ArrayList<>();
    collectColumns(expression, result);
    return result;
  }

  private static void collectColumns(Expression expression, List<ColumnRef> result) {
    switch (expression) {
      case ColumnExpression column -> result.add(column.column());
      case BinaryExpression binary -> {
        collectColumns(binary.left(), result);
        collectColumns(binary.right(), result);
      }
      case LiteralExpression literal -> {}
    }
  }
}
