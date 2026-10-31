package dynamitedb.sm.index;

import dynamitedb.sm.draft.RecordId;
import dynamitedb.sm.draft.Value;
import java.util.List;

/** SM5 equality lookup and entry-maintenance strategy shared by HASH and B_PLUS_TREE. */
public interface EqualityIndex {
  List<RecordId> lookup(Value key);

  void insert(Value key, RecordId rid);

  void remove(Value key, RecordId rid);
}
