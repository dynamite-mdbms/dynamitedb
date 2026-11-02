package dynamitedb.ccm;

import dynamitedb.ccm.lifecycle.Transaction;
import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.ccm.recovery.MaintenanceController;
import dynamitedb.ccm.recovery.TransactionRecoveryBridge;
import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.ProtocolAdmin;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;

public class ConcurrencyManagerImpl
    implements AccessController,
        TransactionManager,
        ProtocolAdmin,
        ChangeLogger,
        RecoveryCoordinator {

  private final TransactionRegistry registry;
  private final ConcurrencyProtocol protocol;
  private final TransactionRecoveryBridge bridge;
  private final MaintenanceController maintenance;
  private ProtocolType activeType = ProtocolType.STRICT_2PL;

  public ConcurrencyManagerImpl(
      TransactionRegistry registry,
      ConcurrencyProtocol protocol,
      TransactionRecoveryBridge bridge,
      MaintenanceController maintenance) {
    this.registry = registry;
    this.protocol = protocol;
    this.bridge = bridge;
    this.maintenance = maintenance;
  }

  @Override
  public AccessDecision requestAccess(AccessRequest request) {
    return protocol.request(request);
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    return protocol.awaitAccess(ticket);
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

  @Override
  public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
    return bridge.recordChange(tx, write);
  }

  @Override
  public MaintenanceGuard acquireMaintenance() {
    return maintenance.acquire();
  }

  @Override
  public long transactionTimestamp(TransactionId tx) {
    Transaction transaction = registry.get(tx);
    return transaction == null ? 0 : transaction.timestamp();
  }

  @Override
  public void initializeRecoveredState(RecoveryHighWater highWater) {
    registry.restoreHighWater(highWater);
  }
}
