package dynamitedb.ccm;

import dynamitedb.ccm.lifecycle.Transaction;
import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.ProtocolAdmin;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;

public class ConcurrencyManagerImpl implements AccessController, TransactionManager, ProtocolAdmin {

  private final TransactionRegistry registry = new TransactionRegistry();
  private ProtocolType activeType = ProtocolType.STRICT_2PL;

  @Override
  public AccessDecision requestAccess(AccessRequest request) {
    return AccessDecision.granted();
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    return AccessDecision.granted();
  }

  @Override
  public TransactionId begin() {
    return registry.create().id();
  }

  @Override
  public void commit(TransactionId tx) {
    Transaction transaction = registry.get(tx);
    if (transaction != null) {
      transaction.transition(TransactionStatus.COMMITTED);
    }
  }

  @Override
  public void abort(TransactionId tx) {
    Transaction transaction = registry.get(tx);
    if (transaction != null) {
      transaction.transition(TransactionStatus.ABORTED);
    }
  }

  @Override
  public TransactionStatus status(TransactionId tx) {
    Transaction transaction = registry.get(tx);
    return transaction == null ? TransactionStatus.ABORTED : transaction.status();
  }

  @Override
  public void setProtocol(ProtocolType type) {
    activeType = type;
  }

  @Override
  public ProtocolType activeProtocol() {
    return activeType;
  }
}
