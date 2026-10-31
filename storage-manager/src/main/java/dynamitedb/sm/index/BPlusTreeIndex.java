package dynamitedb.sm.index;

import dynamitedb.sm.draft.IndexPredicate;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.Value;
import java.util.List;

/** SM5 / Nathanael Shane Bennet: ordered B+ tree strategy template (SM-10-SM-11). */
public final class BPlusTreeIndex implements EqualityIndex, OrderedIndex {
  @Override
  public List<RecordId> lookup(Value key) {
    throw new UnsupportedOperationException("TODO(SM5): B+ tree equality lookup");
  }

  @Override
  public List<RecordId> range(IndexPredicate predicate) {
    // TODO(SM5): Preserve LT/LTE/GT/GTE boundaries and reject unsupported comparisons.
    throw new UnsupportedOperationException("TODO(SM5): B+ tree range lookup");
  }

  @Override
  public void insert(Value key, RecordId rid) {
    // TODO(SM5): Maintain duplicate-key entries and split/rebalance nodes when needed.
    throw new UnsupportedOperationException("TODO(SM5): insert a B+ tree entry");
  }

  @Override
  public void remove(Value key, RecordId rid) {
    // TODO(SM5): Remove this RID and maintain the agreed tree invariants.
    throw new UnsupportedOperationException("TODO(SM5): remove a B+ tree entry");
  }
}
