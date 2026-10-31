package dynamitedb.sm.index;

import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.RowCursor;
import dynamitedb.sm.draft.StoredRow;
import dynamitedb.sm.draft.TransactionId;
import dynamitedb.sm.heap.RecordStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SM5 cursor template: resolves index RIDs through SM1 without exposing physical fetch details. */
public final class IndexRowCursor implements RowCursor {
  private final RecordStore records;
  private final TransactionId tx;
  private final List<RecordId> recordIds;

  public IndexRowCursor(RecordStore records, TransactionId tx, List<RecordId> recordIds) {
    this.records = Objects.requireNonNull(records, "records");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.recordIds = List.copyOf(recordIds);
  }

  @Override
  public Optional<StoredRow> next() {
    // TODO(SM5/SM1): Fetch/decode the next matching live RID and preserve transaction context.
    throw new UnsupportedOperationException("TODO(SM5): advance an index row cursor");
  }

  @Override
  public void close() {
    // TODO(SM5/SM1): Release traversal resources once and reject reads after closure.
    throw new UnsupportedOperationException("TODO(SM5): close an index row cursor");
  }
}
