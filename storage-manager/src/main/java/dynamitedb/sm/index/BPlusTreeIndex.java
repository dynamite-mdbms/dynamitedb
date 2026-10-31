package dynamitedb.sm.index;

import dynamitedb.sm.draft.IndexMetadata;
import dynamitedb.sm.draft.IndexPredicate;
import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.Value;
import java.util.List;
import java.util.Objects;

/** SM5 / Nathanael Shane Bennet: ordered B+ tree strategy template (SM-10-SM-11). */
public final class BPlusTreeIndex implements OrderedIndex {
  private final IndexMetadata metadata;

  public BPlusTreeIndex(IndexMetadata metadata) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    // TODO(SM5): Require B_PLUS_TREE metadata and agree on ordering, node layout and persistence.
  }

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
