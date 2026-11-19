package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.TableDefinition;

/**
 * Validated CREATE TABLE, ready for {@code TableAdmin.createTable}; primary-key columns are already
 * non-nullable in the schema.
 */
public record CreateTablePlan(TableDefinition definition) implements PlanNode {}
