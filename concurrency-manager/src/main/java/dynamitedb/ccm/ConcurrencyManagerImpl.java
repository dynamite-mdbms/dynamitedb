package dynamitedb.ccm;

import dynamitedb.ccm.lifecycle.Transaction;
import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.ccm.recovery.MaintenanceController;
import dynamitedb.ccm.recovery.TransactionRecoveryBridge;
import dynamitedb.ccm.timestamp.TimestampOrderingProtocol;
import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.api.ProtocolAdmin;
import dynamitedb.contracts.api.RecoveryCoordinator;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ProtocolType;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ConcurrencyManagerImpl
    implements AccessController,
        TransactionManager,
        ProtocolAdmin,
        ChangeLogger,
        RecoveryCoordinator {

  private final TransactionRegistry registry;
  private final Map<ProtocolType, ConcurrencyProtocol> protocols;
  private final TransactionRecoveryBridge bridge;
  private final MaintenanceController maintenance;
  private final Consumer<String> announcer;
  private volatile ConcurrencyProtocol protocol;
  private volatile ProtocolType activeType;

  public ConcurrencyManagerImpl(
      TransactionRegistry registry,
      ConcurrencyProtocol protocol,
      TransactionRecoveryBridge bridge,
      MaintenanceController maintenance) {
    this(
        registry,
        Map.of(typeOf(protocol), protocol),
        typeOf(protocol),
        bridge,
        maintenance,
        System.out::println);
  }

  public ConcurrencyManagerImpl(
      TransactionRegistry registry,
      Map<ProtocolType, ConcurrencyProtocol> protocols,
      ProtocolType initial,
      TransactionRecoveryBridge bridge,
      MaintenanceController maintenance,
      Consumer<String> announcer) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    this.announcer = Objects.requireNonNull(announcer, "announcer");
    Objects.requireNonNull(protocols, "protocols");
    Objects.requireNonNull(initial, "initial");
    Map<ProtocolType, ConcurrencyProtocol> available = new EnumMap<>(ProtocolType.class);
    for (Map.Entry<ProtocolType, ConcurrencyProtocol> entry : protocols.entrySet()) {
      available.put(
          Objects.requireNonNull(entry.getKey(), "type"),
          Objects.requireNonNull(entry.getValue(), "protocol"));
    }
    if (!available.containsKey(initial)) {
      throw new IllegalArgumentException(
          "Implementasi protokol awal " + initial.displayName() + " tidak diberikan");
    }
    this.protocols = available;
    this.protocol = available.get(initial);
    this.activeType = initial;
  }

  @Override
  public AccessDecision requestAccess(AccessRequest request) {
    Objects.requireNonNull(request, "request");
    return decide(request.tx(), () -> protocol.request(request));
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    Objects.requireNonNull(ticket, "ticket");
    return decide(ticket.tx(), () -> protocol.awaitAccess(ticket));
  }

  @Override
  public TransactionId begin() {
    Transaction transaction = registry.create();
    try {
      bridge.begin(transaction.id(), transaction.timestamp());
    } catch (RuntimeException failure) {
      transaction.transition(TransactionStatus.FAILED);
      transaction.transition(TransactionStatus.ABORTED);
      finish(transaction);
      throw new DbmsException(
          ErrorCode.WAL_FAILURE,
          "Transaksi " + transaction.id() + " gagal dimulai karena catatan BEGIN tidak tertulis",
          failure);
    }
    return transaction.id();
  }

  @Override
  public void commit(TransactionId tx) {
    Transaction transaction = registry.get(tx);
    synchronized (transaction) {
      if (transaction.status() != TransactionStatus.ACTIVE) {
        throw new DbmsException(
            ErrorCode.INVALID_STATE,
            "Transaksi "
                + tx
                + " tidak dapat melakukan commit karena berstatus "
                + transaction.status());
      }
      transaction.transition(TransactionStatus.PARTIALLY_COMMITTED);
      try {
        bridge.commit(tx);
      } catch (RuntimeException failure) {
        transaction.transition(TransactionStatus.FAILED);
        DbmsException walFailure =
            new DbmsException(
                ErrorCode.WAL_FAILURE,
                "Catatan COMMIT transaksi " + tx + " gagal ditulis sehingga transaksi dibatalkan",
                failure);
        try {
          rollback(transaction);
        } catch (DbmsException rollbackFailure) {
          rollbackFailure.addSuppressed(walFailure);
          throw rollbackFailure;
        }
        finish(transaction);
        throw walFailure;
      }
      transaction.transition(TransactionStatus.COMMITTED);
    }
    finish(transaction);
  }

  @Override
  public void abort(TransactionId tx) {
    abortFlow(registry.get(tx));
  }

  @Override
  public TransactionStatus status(TransactionId tx) {
    return registry.get(tx).status();
  }

  @Override
  public void setProtocol(ProtocolType type) {
    Objects.requireNonNull(type, "type");
    if (type != activeType) {
      ConcurrencyProtocol target = protocols.get(type);
      if (target == null) {
        throw new DbmsException(
            ErrorCode.UNSUPPORTED_OPERATION,
            "Protokol " + type.displayName() + " tidak tersedia pada konfigurasi ini");
      }
      boolean switched =
          registry.runIfIdle(
              () -> {
                protocol = target;
                activeType = type;
              });
      if (!switched) {
        throw new DbmsException(
            ErrorCode.INVALID_STATE,
            "Protokol tidak dapat diganti karena masih ada "
                + registry.activeCount()
                + " transaksi aktif");
      }
    }
    announcer.accept("Protokol concurrency control aktif: " + type.displayName());
  }

  @Override
  public ProtocolType activeProtocol() {
    return activeType;
  }

  @Override
  public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
    Transaction transaction = registry.get(tx);
    synchronized (transaction) {
      if (!transaction.isActive()) {
        throw new DbmsException(ErrorCode.INVALID_STATE, inactiveReason(transaction));
      }
      try {
        return bridge.recordChange(tx, write);
      } catch (DbmsException failure) {
        if (failure.getCode() != ErrorCode.WAL_FAILURE) {
          throw failure;
        }
        try {
          abortFlow(transaction);
        } catch (DbmsException rollbackFailure) {
          rollbackFailure.addSuppressed(failure);
          throw rollbackFailure;
        }
        throw failure;
      }
    }
  }

  @Override
  public MaintenanceGuard acquireMaintenance() {
    return maintenance.acquire();
  }

  @Override
  public long transactionTimestamp(TransactionId tx) {
    return registry.get(tx).timestamp();
  }

  @Override
  public void initializeRecoveredState(RecoveryHighWater highWater) {
    Objects.requireNonNull(highWater, "highWater");
    maintenance.initializeRecoveredState(
        () -> {
          for (TransactionId tx : registry.activeTransactions()) {
            for (ConcurrencyProtocol available : protocols.values()) {
              available.onFinish(tx);
            }
          }
          registry.restoreHighWater(highWater);
        });
  }

  private AccessDecision decide(TransactionId tx, Supplier<AccessDecision> protocolCall) {
    Optional<Transaction> found = registry.find(tx);
    if (found.isEmpty()) {
      return AccessDecision.aborted("Transaksi " + tx + " tidak dikenal");
    }
    Transaction transaction = found.get();
    if (!transaction.isActive()) {
      return AccessDecision.aborted(inactiveReason(transaction));
    }
    AccessDecision decision =
        Objects.requireNonNull(protocolCall.get(), "Protokol mengembalikan keputusan kosong");
    if (decision.state() == AccessState.ABORTED) {
      abortFlow(transaction);
      return decision;
    }
    if (!transaction.isActive()) {
      if (transaction.isFinal()) {
        protocol.onFinish(tx);
      }
      return AccessDecision.aborted(inactiveReason(transaction));
    }
    return decision;
  }

  private void abortFlow(Transaction transaction) {
    synchronized (transaction) {
      switch (transaction.status()) {
        case ABORTED -> {
          return;
        }
        case COMMITTED ->
            throw new DbmsException(
                ErrorCode.INVALID_STATE,
                "Transaksi " + transaction.id() + " sudah commit sehingga tidak dapat dibatalkan");
        case PARTIALLY_COMMITTED ->
            throw new DbmsException(
                ErrorCode.INVALID_STATE,
                "Transaksi " + transaction.id() + " sedang dalam proses commit");
        case ACTIVE -> transaction.transition(TransactionStatus.FAILED);
        case FAILED -> {}
      }
      rollback(transaction);
    }
    finish(transaction);
  }

  private void rollback(Transaction transaction) {
    try {
      bridge.rollback(transaction.id());
    } catch (RuntimeException failure) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE,
          "Rollback transaksi "
              + transaction.id()
              + " gagal sehingga lock dan data protokolnya tetap dipertahankan",
          failure);
    }
    transaction.transition(TransactionStatus.ABORTED);
  }

  private void finish(Transaction transaction) {
    try {
      protocol.onFinish(transaction.id());
    } finally {
      registry.markFinished(transaction);
    }
  }

  private static String inactiveReason(Transaction transaction) {
    return switch (transaction.status()) {
      case ABORTED, FAILED -> "Transaksi " + transaction.id() + " telah dibatalkan";
      case COMMITTED -> "Transaksi " + transaction.id() + " sudah commit";
      case PARTIALLY_COMMITTED -> "Transaksi " + transaction.id() + " sedang dalam proses commit";
      case ACTIVE -> "Transaksi " + transaction.id() + " masih aktif";
    };
  }

  private static ProtocolType typeOf(ConcurrencyProtocol protocol) {
    Objects.requireNonNull(protocol, "protocol");
    return protocol instanceof TimestampOrderingProtocol
        ? ProtocolType.STRICT_TIMESTAMP
        : ProtocolType.STRICT_2PL;
  }
}
