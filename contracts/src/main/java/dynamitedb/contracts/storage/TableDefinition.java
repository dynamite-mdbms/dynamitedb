package dynamitedb.contracts.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Hasil bind CREATE TABLE: schema beserta key constraints. */
public record TableDefinition(
    String name, Schema schema, Optional<PrimaryKey> primaryKey, List<ForeignKey> foreignKeys) {
  public TableDefinition {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(primaryKey, "primaryKey");
    foreignKeys = List.copyOf(Objects.requireNonNull(foreignKeys, "foreignKeys"));
  }
}
