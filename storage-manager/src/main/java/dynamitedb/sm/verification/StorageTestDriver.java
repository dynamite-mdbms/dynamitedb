package dynamitedb.sm.verification;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.testing.TestReport;
import java.util.List;
import java.util.Objects;

/** Driver pengujian SM lewat interface publiknya (SM-15). */
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
    // TODO: skenario CRUD, halaman, serialisasi, dan index bersama SM2 sampai SM5
    return new TestReport(List.of());
  }
}
