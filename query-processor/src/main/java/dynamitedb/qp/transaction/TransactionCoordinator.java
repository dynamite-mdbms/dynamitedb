package dynamitedb.qp.transaction;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.CatalogAccess;
import dynamitedb.contracts.transaction.AccessController;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionManager;
import dynamitedb.qp.session.SessionContext;
import java.util.Optional;

/** Mengatur BEGIN, COMMIT, ABORT, dan izin akses ke CCM (QP-12). */
public class TransactionCoordinator {

  private final TransactionManager tx;
  private final AccessController access;
  private final CatalogAccess catalog;

  public TransactionCoordinator(
      TransactionManager tx, AccessController access, CatalogAccess catalog) {
    this.tx = tx;
    this.access = access;
    this.catalog = catalog;
  }

  public TransactionId begin(SessionContext session) {
    if (session.activeTx().isPresent()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Transaction is already active");
    }
    // TODO: ambil id dari tx.begin()
    TransactionId id = new TransactionId(1);
    session.setActiveTx(Optional.of(id));
    return id;
  }

  public void authorize(AccessRequest request) {
    // TODO: WAITING ditunggu lewat access.awaitAccess, ABORTED dilempar sebagai ACCESS_ABORTED
  }

  public void authorizeTable(TransactionId tx, String table, AccessMode mode) {
    // TODO: bikin ResourceId TABLE dari catalog.getTableId(table), lalu panggil authorize
  }

  public void commit(SessionContext session) {
    requireActiveTx(session);
    session.setActiveTx(Optional.empty());
  }

  public void abort(SessionContext session) {
    requireActiveTx(session);
    session.setActiveTx(Optional.empty());
  }

  private void requireActiveTx(SessionContext session) {
    if (session.activeTx().isEmpty()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "No active transaction");
    }
  }
}
