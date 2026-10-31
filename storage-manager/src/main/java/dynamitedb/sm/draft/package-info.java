/**
 * Local compilation placeholders for SM1-SM5 ports and values missing from the contracts module.
 *
 * <p>These types are not approved shared APIs. Replace their imports with the agreed
 * dynamitedb.contracts types and remove this package once those contracts are available. QO3 owns
 * CatalogAccess; SM4 provides its StorageCatalogAdapter implementation. FRM owns the recovery,
 * manifest and WAL durability ports; this package supplies declarations only. The shared value
 * records follow the architecture's Java 21 field shapes. Proposed internal method signatures still
 * need agreement with their owners.
 */
package dynamitedb.sm.draft;
