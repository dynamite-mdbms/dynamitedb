package dynamitedb.qp.operator;

import dynamitedb.contracts.query.OperatorRow;
import java.util.List;
import java.util.Optional;

// Buat pengganti test untuk scanoperator.
final class ListOperator implements Operator {

  private final List<OperatorRow> rows;
  private int pos;
  private boolean isOpen;
  int openCount;
  int closeCount;

  ListOperator(List<OperatorRow> rows) {
    this.rows = List.copyOf(rows);
  }

  @Override
  public void open() {
    pos = 0;
    isOpen = true;
    openCount++;
  }

  @Override
  public Optional<OperatorRow> next() {
    if (!isOpen) {
      throw new IllegalStateException("next() dipanggil sebelum open()");
    }

    return pos < rows.size() ? Optional.of(rows.get(pos++)) : Optional.empty();
  }

  @Override
  public void close() {
    isOpen = false;
    closeCount++;
  }
}
