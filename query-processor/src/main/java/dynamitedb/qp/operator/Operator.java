package dynamitedb.qp.operator;

import dynamitedb.contracts.storage.Row;

/** Interface buat semua operator (scan, filter, project, join, sort). */
public interface Operator {

  void open();

  /** Return null kalau barisnya udah habis. */
  Row next();

  void close();
}
