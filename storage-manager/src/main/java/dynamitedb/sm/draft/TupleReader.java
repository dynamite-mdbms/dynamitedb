package dynamitedb.sm.draft;

import java.util.Optional;

/** Temporary SM1 read port; callers obtain CCM permission first and must close returned cursors. */
public interface TupleReader {
  RowCursor scanTable(String table, TransactionId tx);

  RowCursor scanIndex(String index, IndexPredicate predicate, TransactionId tx);

  Optional<StoredRow> fetch(RecordId rid, TransactionId tx);
}
