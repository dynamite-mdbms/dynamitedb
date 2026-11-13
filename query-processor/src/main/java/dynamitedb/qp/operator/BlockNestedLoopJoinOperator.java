package dynamitedb.qp.operator;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import dynamitedb.qp.expression.ExpressionEvaluator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class BlockNestedLoopJoinOperator implements Operator {
  private static final int BLOCK_SIZE = 100; // 100 dulu buat skr
  private final Operator left;
  private final Operator right;
  private final JoinKind kind;
  private final Optional<Expression> condition;
  private final ExpressionEvaluator evaluator;

  private final List<OperatorRow> block = new ArrayList<>();
  private OperatorRow curRight;
  private int position;
  private boolean leftEmpty;
  private boolean rightOpen;
  private boolean isOpen;

  private int[] leftJoinIdx;
  private int[] rightJoinIdx;
  private boolean[] isDuplicateRight;

  public BlockNestedLoopJoinOperator(
      Operator left,
      Operator right,
      JoinKind kind,
      Optional<Expression> condition,
      ExpressionEvaluator evaluator) {
    this.left = Objects.requireNonNull(left, "left");
    this.right = Objects.requireNonNull(right, "right");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.condition = Objects.requireNonNull(condition, "condition");
    this.evaluator = evaluator;

    if (kind == JoinKind.THETA && (condition.isEmpty() || evaluator == null)) {
      throw new IllegalArgumentException(
          "Conditional join perlu kondisi ON dan ExpressionEvaluator");
    }
  }

  @Override
  public void open() {
    if (isOpen) {
      close();
    }

    left.open();
    block.clear();

    curRight = null;
    position = 0;
    leftEmpty = false;
    leftJoinIdx = null;
    rightJoinIdx = null;
    isDuplicateRight = null;
    isOpen = true;
  }

  @Override
  public Optional<OperatorRow> next() {
    if (!isOpen) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "BlockNestedLoopJoinOperator harus sudah di open()");
    }

    while (true) {
      if (curRight != null) {
        while (position < block.size()) {
          OperatorRow l = block.get(position++);
          Optional<OperatorRow> joined = matchAndMerge(l, curRight);

          if (joined.isPresent()) {
            return joined;
          }
        }
        curRight = null;
      }

      if (!block.isEmpty()) {
        Optional<OperatorRow> r = right.next();
        if (r.isPresent()) {
          curRight = r.get();
          position = 0;
          continue;
        }
        closeRight();
        block.clear();
      }

      if (leftEmpty || !insertBlock()) {
        return Optional.empty();
      }

      right.open();
      rightOpen = true;
    }
  }

  @Override
  public void close() {
    left.close();
    closeRight();
    block.clear();
    curRight = null;
    isOpen = false;
  }

  // Helper
  private void closeRight() {
    if (rightOpen) {
      right.close();
      rightOpen = false;
    }
  }

  private boolean insertBlock() {
    while (block.size() < BLOCK_SIZE) {
      Optional<OperatorRow> l = left.next();
      if (l.isEmpty()) {
        leftEmpty = true;
        break;
      }
      block.add(l.get());
    }

    return !block.isEmpty();
  }

  private Optional<OperatorRow> matchAndMerge(OperatorRow l, OperatorRow r) {
    switch (kind) {
      case CARTESIAN:
        return Optional.of(concat(l, r));
      case THETA:
        {
          OperatorRow joined = concat(l, r);
          return evaluator.evaluatePredicate(condition.get(), joined)
              ? Optional.of(joined)
              : Optional.empty();
        }
      case NATURAL:
        {
          if (leftJoinIdx == null) {
            planNaturalJoin(l.schema(), r.schema());
          }
          return isNaturalMatch(l, r) ? Optional.of(naturalMerge(l, r)) : Optional.empty();
        }

      default:
        throw new IllegalStateException("JoinKind tidak dikenal: " + kind);
    }
  }

  static OperatorRow concat(OperatorRow l, OperatorRow r) {
    List<Value> values = new ArrayList<>(l.row().values());
    values.addAll(r.row().values());

    List<Column> cols = new ArrayList<>(l.schema().columns());
    cols.addAll(r.schema().columns());

    return new OperatorRow(new Row(values), new Schema(cols), mergeOrigins(l, r));
  }

  static Map<String, RecordId> mergeOrigins(OperatorRow l, OperatorRow r) {
    Map<String, RecordId> origins = new HashMap<>(l.origins());
    origins.putAll(r.origins());
    return origins;
  }

  // Cari pasangan kolom yg namanya sama di left dan right, terus disimpen di field.
  private void planNaturalJoin(Schema ls, Schema rs) {
    List<Integer> lk = new ArrayList<>();
    List<Integer> rk = new ArrayList<>();
    boolean[] drop = new boolean[rs.columns().size()];
    for (int j = 0; j < rs.columns().size(); j++) {
      String name = rs.columns().get(j).name();
      for (int i = 0; i < ls.columns().size(); i++) {
        if (ls.columns().get(i).name().equalsIgnoreCase(name)) {
          lk.add(i);
          rk.add(j);
          drop[j] = true;
          break;
        }
      }
    }

    leftJoinIdx = lk.stream().mapToInt(Integer::intValue).toArray();
    rightJoinIdx = rk.stream().mapToInt(Integer::intValue).toArray();
    isDuplicateRight = drop;
  }

  private boolean isNaturalMatch(OperatorRow l, OperatorRow r) {
    for (int k = 0; k < leftJoinIdx.length; k++) {
      Value a = l.row().values().get(leftJoinIdx[k]);
      Value b = r.row().values().get(rightJoinIdx[k]);

      if (a instanceof NullValue
          || b instanceof NullValue
          || ExpressionEvaluator.compare(a, b) != 0) {
        return false;
      }
    }
    return true;
  }

  private OperatorRow naturalMerge(OperatorRow l, OperatorRow r) {
    List<Value> values = new ArrayList<>(l.row().values());
    List<Column> cols = new ArrayList<>(l.schema().columns());

    for (int j = 0; j < r.row().values().size(); j++) {
      if (!isDuplicateRight[j]) {
        values.add(r.row().values().get(j));
        cols.add(r.schema().columns().get(j));
      }
    }

    return new OperatorRow(new Row(values), new Schema(cols), mergeOrigins(l, r));
  }
}
