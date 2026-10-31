package dynamitedb.sm.index;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Value;
import java.util.List;

/** Strategi index untuk pencarian kesamaan, dipakai HASH dan B_PLUS_TREE. */
public interface EqualityIndex {
  List<RecordId> lookup(Value key);

  void insert(Value key, RecordId rid);

  void remove(Value key, RecordId rid);
}
