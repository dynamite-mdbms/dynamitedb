package dynamitedb.sm.index;

import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.Value;
import java.util.List;

/** SM5 / Nathanael Shane Bennet: equality-only HASH strategy template (SM-10-SM-11). */
public final class HashIndex implements EqualityIndex {
  @Override
  public List<RecordId> lookup(Value key) {
    // TODO(SM5): Return matching RIDs without promising range capabilities.
    throw new UnsupportedOperationException("TODO(SM5): hash equality lookup");
  }

  @Override
  public void insert(Value key, RecordId rid) {
    // TODO(SM5): Maintain duplicate-key RID entries using the agreed SQL value semantics.
    throw new UnsupportedOperationException("TODO(SM5): insert a hash index entry");
  }

  @Override
  public void remove(Value key, RecordId rid) {
    // TODO(SM5): Remove only this RID and preserve other rows with the same key.
    throw new UnsupportedOperationException("TODO(SM5): remove a hash index entry");
  }
}
