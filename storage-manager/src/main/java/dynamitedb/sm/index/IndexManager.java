package dynamitedb.sm.index;

import dynamitedb.sm.draft.IndexMetadata;
import dynamitedb.sm.draft.IndexPredicate;
import dynamitedb.sm.draft.PreparedWrite;
import dynamitedb.sm.draft.RecordId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SM5 / Nathanael Shane Bennet: strategy routing, maintenance and administration template. */
public final class IndexManager {
  private final Map<String, EqualityIndex> indexes = new HashMap<>();

  public void createIndex(IndexMetadata meta) {
    // TODO(SM5/SM1): Obtain a heap-row source and build the requested HASH or B_PLUS_TREE strategy.
    // TODO(SM5/SM2/SM4): Make the index complete/durable before atomically publishing its
    // definition.
    // Failure must leave no partial publication; QP supplies implicit table-WRITE protection.
    throw new UnsupportedOperationException("TODO(SM5): create and publish a durable index");
  }

  public List<RecordId> lookup(String index, IndexPredicate predicate) {
    // TODO(SM5): Resolve the named strategy; dispatch EQ to either strategy, ranges to
    // OrderedIndex.
    // HASH cannot satisfy range predicates; NEQ requires an alternate access path/filter.
    throw new UnsupportedOperationException("TODO(SM5): route an index lookup");
  }

  public void rebuildAll() {
    // TODO(SM5/SM1/SM4/FRM): Rebuild retained definitions from the recovered heap before serving.
    throw new UnsupportedOperationException("TODO(SM5): rebuild indexes from recovered heap");
  }

  public void onWrite(PreparedWrite write) {
    // TODO(SM5/SM1/SM3): Decode before/after keys and maintain entries with the matching heap
    // write.
    throw new UnsupportedOperationException("TODO(SM5): maintain indexes for a row mutation");
  }
}
