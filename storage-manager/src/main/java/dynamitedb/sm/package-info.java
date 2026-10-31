/**
 * Storage manager boilerplate aligned with 04_storage_manager.puml in the Class Diagrams archive.
 *
 * <ul>
 *   <li>SM1 / Jason Edward Salim: facade, heap, generation and verification packages (SM-04-SM-07,
 *       SM-15).
 *   <li>SM2 / Raynard Fausta: buffer package, including disk and page templates (SM-01,
 *       SM-12-SM-13).
 *   <li>SM3 / Zeki Amani: serialization package and row/page format agreement (SM-02-SM-03).
 *   <li>SM4 / Audric Yusuf Maynard Simatupang: catalog package (SM-08-SM-09, SM-14).
 *   <li>SM5 / Nathanael Shane Bennet: index package, including IndexRowCursor (SM-10-SM-11).
 * </ul>
 *
 * <p>draft contains temporary ports and immutable value shapes for compilation. Replace those with
 * agreed dynamitedb.contracts imports once available; other modules must not import local drafts.
 * Constructors only initialize fields. Operational stubs throw UnsupportedOperationException;
 * adapters may delegate to those stubs. No storage algorithms, persistence, mocks or test scenarios
 * are implemented. Diagram method names, parameters, fields and relationships are preserved;
 * constructors provide local assembly scaffolding. DbmsBootstrap in app will compose the concrete
 * classes after contract agreement.
 */
package dynamitedb.sm;
