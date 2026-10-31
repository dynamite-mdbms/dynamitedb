package dynamitedb.sm.index;

import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.draft.IndexKind;
import dynamitedb.sm.draft.IndexPredicate;
import dynamitedb.sm.draft.PreparedWrite;
import dynamitedb.sm.draft.RecordId;
import java.util.List;
import java.util.Objects;

/** SM5 / Nathanael Shane Bennet: strategy routing, maintenance and administration template. */
public final class IndexManager {
  private final CatalogManager catalog;

  public IndexManager(CatalogManager catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public List<RecordId> lookup(String index, IndexPredicate predicate) {
    // TODO(SM5): Resolve catalog metadata and dispatch EQ to either strategy, ranges to ordered.
    // HASH cannot satisfy range predicates; NEQ requires an alternate access path/filter.
    throw new UnsupportedOperationException("TODO(SM5): route an index lookup");
  }

  public void maintainOnWrite(PreparedWrite prepared) {
    // TODO(SM5/SM1/SM3): Decode before/after keys and maintain entries with the matching heap
    // write.
    throw new UnsupportedOperationException("TODO(SM5): maintain indexes for a row mutation");
  }

  public void createIndex(String index, String table, String column, IndexKind kind) {
    // TODO(SM5/SM1): Obtain a heap-row source and build a complete isolated index.
    // TODO(SM5/SM2/SM4): Make it durable before atomically publishing catalog metadata.
    // Failure must leave no partially published index; QP supplies implicit table-WRITE protection.
    throw new UnsupportedOperationException("TODO(SM5): create and publish a durable index");
  }

  public void rebuildAll() {
    // TODO(SM5/SM1/FRM): Agree on a recovered heap-row source and rebuild retained definitions.
    // Recovery must complete this before queries resume; do not create a second metadata source.
    throw new UnsupportedOperationException("TODO(SM5): rebuild indexes from recovered heap");
  }
}
