package dynamitedb.sm.verification;

import dynamitedb.sm.draft.CatalogAccess;
import dynamitedb.sm.draft.IndexAdmin;
import dynamitedb.sm.draft.TestReport;
import dynamitedb.sm.draft.TupleReader;
import dynamitedb.sm.draft.WriteStore;
import java.util.Objects;

/** SM1 / Jason Edward Salim: port-based test-driver template (SM-15); no scenarios exist yet. */
public final class StorageTestDriver {
  private final TupleReader reader;
  private final WriteStore writes;
  private final IndexAdmin indexes;
  private final CatalogAccess catalog;

  public StorageTestDriver(
      TupleReader reader, WriteStore writes, IndexAdmin indexes, CatalogAccess catalog) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.indexes = Objects.requireNonNull(indexes, "indexes");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public TestReport runAll() {
    // TODO(SM1/SM-15): Agree on fixtures and CRUD/page/serialization/index scenarios with SM2-SM5.
    // Use the four consumer ports shown in the diagram; no concrete facade dependency is needed.
    // Return actual scenario results once implemented; do not fabricate a passing report.
    throw new UnsupportedOperationException("TODO(SM1): run agreed storage verification scenarios");
  }
}
