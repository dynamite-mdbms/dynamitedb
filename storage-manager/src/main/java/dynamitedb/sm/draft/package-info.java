/**
 * Local compilation placeholders for SM1-SM5 ports and values missing from the contracts module.
 *
 * <p>These types are not approved shared APIs. Replace their imports with the agreed
 * dynamitedb.contracts types and remove this package once those contracts are available. QO3 owns
 * CatalogAccess; SM4 provides its StorageCatalogAdapter implementation. FRM owns the recovery,
 * manifest and WAL durability ports; this package supplies declarations only. The shared value
 * records follow 01_shared_types.puml; the SM ports follow 04_storage_manager.puml in the supplied
 * Class Diagrams archive. Replace these local declarations with agreed contracts during
 * integration.
 */
package dynamitedb.sm.draft;
