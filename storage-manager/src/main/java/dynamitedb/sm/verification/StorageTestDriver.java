package dynamitedb.sm.verification;

import dynamitedb.sm.draft.TestReport;
import dynamitedb.sm.facade.StorageManagerImpl;
import java.util.Objects;

/** SM1 / Jason Edward Salim: test-driver entry point template (SM-15); no scenarios exist yet. */
public final class StorageTestDriver {
  private final StorageManagerImpl storage;

  public StorageTestDriver(StorageManagerImpl storage) {
    this.storage = Objects.requireNonNull(storage, "storage");
  }

  public TestReport runAll() {
    // TODO(SM1/SM-15): Agree on fixtures and CRUD/page/serialization/index scenarios with SM2-SM5.
    // Return actual scenario results once implemented; do not fabricate a passing report.
    throw new UnsupportedOperationException("TODO(SM1): run agreed storage verification scenarios");
  }
}
