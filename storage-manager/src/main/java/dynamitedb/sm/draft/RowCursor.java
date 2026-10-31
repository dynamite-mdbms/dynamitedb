package dynamitedb.sm.draft;

import java.util.Optional;

/** Temporary SM1 closeable stream; Optional.empty denotes exhaustion rather than a SQL NULL. */
public interface RowCursor extends AutoCloseable {
  Optional<StoredRow> next();

  @Override
  void close();
}
