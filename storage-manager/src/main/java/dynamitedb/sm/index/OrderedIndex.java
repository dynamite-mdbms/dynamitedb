package dynamitedb.sm.index;

import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.RecordId;
import java.util.List;

/** Kemampuan pencarian rentang LT/LTE/GT/GTE. */
public interface OrderedIndex extends EqualityIndex {
  List<RecordId> range(IndexPredicate predicate);
}
