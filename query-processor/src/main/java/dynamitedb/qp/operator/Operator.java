package dynamitedb.qp.operator;

import dynamitedb.contracts.query.OperatorRow;
import java.util.Optional;

/** Interface buat semua operator (scan, filter, project, join, sort). */
public interface Operator {

  void open();

  /** Optional kosong kalau barisnya udah habis. */
  Optional<OperatorRow> next();

  void close();
}
