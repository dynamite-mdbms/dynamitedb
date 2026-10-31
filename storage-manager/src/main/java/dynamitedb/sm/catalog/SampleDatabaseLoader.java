package dynamitedb.sm.catalog;

import java.nio.file.Path;
import java.util.Objects;

/**
 * SM4 initial/sample database loading template (SM-14).
 *
 * <p>Bootstrap invokes initialization separately from ordinary query execution. An existing
 * database must be opened and recovered rather than overwritten with sample data.
 */
public final class SampleDatabaseLoader {
  private final CatalogManager catalog;

  public SampleDatabaseLoader(CatalogManager catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /**
   * Initializes sample data only if the target database is absent.
   *
   * <p>Bootstrap supplies the data-directory value from its configuration. Passing {@link Path}
   * avoids importing the application-owned DbmsConfig into the storage manager.
   *
   * @param dataDirectory directory in which the database should be initialized
   */
  public void loadIfAbsent(Path dataDirectory) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    // TODO(SM4 / SM-14): Check the authoritative database/manifest state before initialization.
    // Existing, corrupt and partially initialized databases must not be treated as absent.
    // TODO(SM4): Agree on sample schemas/data and initialization ports with SM1 and bootstrap.
    // Use catalog for stable table IDs, schemas and initial statistics; keep row writes with SM1.
    // TODO(SM4): Coordinate durable initialization and completion with storage/recovery owners.
    // A retry must preserve existing data and must not duplicate a partially loaded dataset.
    throw new UnsupportedOperationException("TODO(SM4): load the initial database only if absent");
  }
}
