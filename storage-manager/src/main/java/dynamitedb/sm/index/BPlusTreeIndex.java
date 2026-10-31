package dynamitedb.sm.index;

import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Value;
import java.util.List;

/** Index B+Tree untuk pencarian kesamaan dan rentang (SM-10, SM-11). */
public final class BPlusTreeIndex implements EqualityIndex, OrderedIndex {
  @Override
  public List<RecordId> lookup(Value key) {
    // TODO: cari RID dengan key yang sama
    return List.of();
  }

  @Override
  public List<RecordId> range(IndexPredicate predicate) {
    // TODO: rentang LT/LTE/GT/GTE sesuai batasnya
    return List.of();
  }

  @Override
  public void insert(Value key, RecordId rid) {
    // TODO: dukung key duplikat, split node jika penuh
  }

  @Override
  public void remove(Value key, RecordId rid) {
    // TODO: hapus RID dan jaga invarian tree
  }
}
