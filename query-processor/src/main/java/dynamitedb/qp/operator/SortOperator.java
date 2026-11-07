package dynamitedb.qp.operator;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.qp.expression.ExpressionEvaluator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SortOperator implements Operator {
  private final Operator child;
  private final List<SortKey> keys;

  private List<OperatorRow> sorted; // null = belum dimaterialisasi
  private int position;
  private boolean isOpen;

  public SortOperator(Operator child, List<SortKey> keys) {
    this.child = Objects.requireNonNull(child, "child");
    this.keys = List.copyOf(Objects.requireNonNull(keys, "keys"));

    if (this.keys.isEmpty()) {
      throw new IllegalArgumentException("SortOperator butuh minimal satu SortKey");
    }
  }

  @Override
  public void open() {
    child.open();
    sorted = null; 
    position = 0;
    isOpen = true;
  }

  @Override
  public Optional<OperatorRow> next() {
    if (!isOpen) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "SortOperator harus sudah di open()");
    }

    if (sorted == null) {
      materialize();
    }

    if (position < sorted.size()) {
      Optional<OperatorRow> result = Optional.of(sorted.get(position));
      position++;
      return result;
    } else {
        return Optional.empty();
    }
  }

  @Override
  public void close() {
    child.close();
    sorted = null;
    isOpen = false;
  }

  private void materialize() {
    List<OperatorRow> rows = new ArrayList<>();
    for (Optional<OperatorRow> r = child.next(); r.isPresent(); r = child.next()) {
      rows.add(r.get());
    }

    if (!rows.isEmpty()) {
      rows.sort(createComparator(rows.get(0)));
    }

    sorted = rows;
    position = 0;
  }

  private Comparator<OperatorRow> createComparator(OperatorRow sample) {
    Comparator<OperatorRow> result = null;

    for (SortKey key : keys) {
      int idx = sample.findColumnIndex(key.column());
      Comparator<OperatorRow> c = (r1, r2) -> compareForSort(r1.row().values().get(idx), r2.row().values().get(idx));
      if (key.direction() == SortDirection.DESC) {
        c = c.reversed();
      } 

      if (result == null) {
          result = c;
      } else {
          result = result.thenComparing(c);
      }
    }

    return result;
  }

  // Null dianggep nilainya paling kecil
  private static int compareForSort(Value a, Value b) {
    boolean aNull = a instanceof NullValue;
    boolean bNull = b instanceof NullValue;

    if (aNull && bNull) {
      return 0;
    }
    if (aNull) {
      return -1;
    }
    if (bNull) {
      return 1;
    }

    return ExpressionEvaluator.compare(a, b);
  }
}
