package dynamitedb.qp.transaction;

import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.qp.session.SessionContext;
import java.util.Objects;
import java.util.Optional;

/** Mengatur BEGIN, COMMIT, ABORT, dan izin akses ke CCM (QP-12). */
public class TransactionCoordinator {

  private final TransactionManager tx;
  private final AccessController access;
  private final CatalogAccess catalog;

  public TransactionCoordinator(
      TransactionManager tx, AccessController access, CatalogAccess catalog) {
    this.tx = Objects.requireNonNull(tx, "tx");
    this.access = Objects.requireNonNull(access, "access");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public TransactionId begin(SessionContext session) {
    if (session.activeTx().isPresent()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Transaction is already active");
    }
    TransactionId id = tx.begin();
    if (id == null) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "CCM did not return a transaction id");
    }
    session.setActiveTx(Optional.of(id));
    return id;
  }

  // blocking: baru balik setelah GRANTED, WAITING bukan izin
  public void authorize(AccessRequest request) {
    AccessDecision decision = access.requestAccess(request);
    while (true) {
      if (decision == null) {
        throw new DbmsException(ErrorCode.INVALID_STATE, "CCM returned no access decision");
      }
      switch (decision.state()) {
        case GRANTED -> {
          return;
        }
        case WAITING -> decision = access.awaitAccess(decision.ticket().orElseThrow());
        case ABORTED ->
            throw new DbmsException(
                ErrorCode.ACCESS_ABORTED, "Transaction aborted by CCM: " + decision.reason());
      }
    }
  }

  // baseline lock per tabel, sesuai kontrak C
  public void authorizeTable(TransactionId tx, String table, AccessMode mode) {
    ResourceId resource = ResourceId.table(catalog.getTableId(table));
    authorize(new AccessRequest(tx, resource, mode));
  }

  // kalau commit gagal, activeTx dibiarkan supaya QueryProcessorImpl bisa abort
  public void commit(SessionContext session) {
    TransactionId id = requireActiveTx(session);
    tx.commit(id);
    session.setActiveTx(Optional.empty());
  }

  public void abort(SessionContext session) {
    TransactionId id = requireActiveTx(session);
    // tx yang sudah di-abort CCM (deadlock/timestamp) tidak di-abort ulang
    if (tx.status(id) != TransactionStatus.ABORTED) {
      tx.abort(id);
    }
    session.setActiveTx(Optional.empty());
  }

  private TransactionId requireActiveTx(SessionContext session) {
    return session
        .activeTx()
        .orElseThrow(() -> new DbmsException(ErrorCode.INVALID_STATE, "No active transaction"));
  }
}
