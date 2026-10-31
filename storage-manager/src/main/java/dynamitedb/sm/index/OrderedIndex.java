package dynamitedb.sm.index;

import dynamitedb.sm.draft.IndexPredicate;
import dynamitedb.sm.draft.RecordId;
import java.util.List;

/** SM5 ordered capability; LT/LTE/GT/GTE preserve their exclusive/inclusive bounds. */
public interface OrderedIndex extends EqualityIndex {
  /** NEQ is not an implicit range scan; equality is supplied by EqualityIndex.lookup. */
  List<RecordId> range(IndexPredicate predicate);
}
