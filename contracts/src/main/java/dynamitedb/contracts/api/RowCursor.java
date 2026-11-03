package dynamitedb.contracts.api;

import dynamitedb.contracts.storage.StoredRow;
import java.util.Optional;

/** Iterasi baris hasil scan, Optional kosong berarti baris sudah habis. */
public interface RowCursor extends AutoCloseable {
  Optional<StoredRow> next();

  @Override
  void close();
}
