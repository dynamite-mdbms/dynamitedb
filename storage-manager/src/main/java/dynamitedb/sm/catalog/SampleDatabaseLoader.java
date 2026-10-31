package dynamitedb.sm.catalog;

import java.util.Objects;

/** SM4 / Audric Yusuf Maynard Simatupang: initial/sample database entry point (SM-14). */
public final class SampleDatabaseLoader {
  public void loadIfAbsent(CatalogManager catalog) {
    Objects.requireNonNull(catalog, "catalog");
    // TODO(SM4/SM1): Agree on sample schemas/data and the initial heap/row-loading port.
    // TODO(bootstrap/FRM): Resolve configured storage and manifest state through initialization
    // wiring. Existing, corrupt or partially initialized databases must not be treated as absent.
    // TODO(SM4): Make initialization durable and retry-safe without overwriting existing data.
    throw new UnsupportedOperationException("TODO(SM4): load the initial database only if absent");
  }
}
