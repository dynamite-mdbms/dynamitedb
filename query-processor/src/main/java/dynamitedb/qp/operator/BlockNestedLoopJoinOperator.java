package dynamitedb.qp.operator;

import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.Optional;

public class BlockNestedLoopJoinOperator implements Operator {
  private static final int BLOCK_SIZE = 100; // 100 dulu buat skr
  private final Operator left;
  private final Operator right;
  private final JoinKind kind;
  private final Optional<Expression> condition;
  private final ExpressionEvaluator evaluator;

  public BlockNestedLoopJoinOperator(
      Operator left,
      Operator right,
      JoinKind kind,
      Optional<Expression> condition,
      ExpressionEvaluator evaluator) {
    this.left = left;
    this.right = right;
    this.kind = kind;
    this.condition = condition;
    this.evaluator = evaluator;
  }

  @Override
  public void open() {
    // TODO: open left, siapkan blok
  }

  @Override
  public Optional<OperatorRow> next() {
    // TODO: isi blok dari left, rescan right per blok, pasangkan sesuai kind
    return Optional.empty();
  }

  @Override
  public void close() {
    // TODO: close left dan right
  }
}
