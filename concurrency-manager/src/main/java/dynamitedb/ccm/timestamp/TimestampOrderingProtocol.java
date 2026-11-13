package dynamitedb.ccm.timestamp;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Protokol strict timestamp ordering. Transaksi lebih muda yang bertabrakan dengan penulisan
 * belum-final milik transaksi lebih tua menunggu sampai pemiliknya commit atau abort (panduan
 * 1617-1651), lalu keputusan dievaluasi ulang; transaksi lebih tua langsung di-abort.
 */
public class TimestampOrderingProtocol implements ConcurrencyProtocol {
  private final TimestampRegistry timestamps;
  private final StrictWriteIntentTable intents;
  private final TransactionRegistry registry;
  private final Map<WaitToken, PendingWait> pendingWaits = new HashMap<>();

  private static final class PendingWait {
    final AccessRequest request;
    boolean cancelled;

    PendingWait(AccessRequest request) {
      this.request = request;
    }
  }

  public TimestampOrderingProtocol(
      TimestampRegistry timestamps, StrictWriteIntentTable intents, TransactionRegistry registry) {
    this.timestamps = Objects.requireNonNull(timestamps, "timestamps");
    this.intents = Objects.requireNonNull(intents, "intents");
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public synchronized AccessDecision request(AccessRequest request) {
    TransactionId tx = request.tx();
    long ts = registry.get(tx).timestamp();
    var resource = request.resource();
    switch (request.mode()) {
      case READ -> {
        if (timestamps.writeTs(resource) > ts) {
          return AccessDecision.aborted(writeTsReason(resource, tx, timestamps.writeTs(resource)));
        }
        Optional<TransactionId> owner = intents.conflictingOwner(resource, tx);
        if (owner.isPresent()) {
          return waitForOwner(request, owner.get());
        }
        timestamps.markRead(resource, ts);
      }
      case WRITE -> {
        if (timestamps.readTs(resource) > ts) {
          return AccessDecision.aborted(readTsReason(resource, tx));
        }

        if (timestamps.writeTs(resource) > ts) {
          return AccessDecision.aborted(writeTsReason(resource, tx, timestamps.writeTs(resource)));
        }

        Optional<TransactionId> owner = intents.conflictingOwner(resource, tx);
        if (owner.isPresent()) {
          return waitForOwner(request, owner.get());
        }
        timestamps.markWrite(resource, ts);
        intents.holdUntilFinal(tx, resource);
      }
    }
    return AccessDecision.granted();
  }

  @Override
  public synchronized AccessDecision awaitAccess(WaitToken ticket) {
    Objects.requireNonNull(ticket, "ticket");
    PendingWait pending = pendingWaits.remove(ticket);
    if (pending == null) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE,
          "Protokol timestamp tidak menunggu; tidak ada ticket untuk diakses");
    }
    while (true) {
      if (pending.cancelled) {
        return AccessDecision.aborted(
            "Akses tertunda transaksi " + pending.request.tx() + " telah dibatalkan");
      }
      var transaction = registry.find(pending.request.tx()).orElse(null);
      if (transaction == null || !transaction.isActive()) {
        return AccessDecision.aborted(
            "Transaksi " + pending.request.tx() + " telah berakhir selama penantian");
      }
      if (intents.conflictingOwner(pending.request.resource(), pending.request.tx()).isEmpty()) {
        return grantAfterWait(pending.request);
      }
      try {
        wait();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return AccessDecision.aborted(
            "Penantian akses transaksi " + pending.request.tx() + " terinterupsi");
      }
    }
  }

  @Override
  public synchronized void onFinish(TransactionId tx) {
    intents.release(tx);
    pendingWaits
        .values()
        .forEach(pending -> pending.cancelled |= pending.request.tx().equals(tx));
    notifyAll();
  }

  /** Pemilik intent belum final: transaksi lebih muda menunggu, transaksi lebih tua di-abort. */
  private AccessDecision waitForOwner(AccessRequest request, TransactionId owner) {
    WaitToken ticket = new WaitToken(request.tx(), UUID.randomUUID());
    pendingWaits.put(ticket, new PendingWait(request));
    return AccessDecision.waiting(
        ticket,
        "Transaksi "
            + request.tx()
            + " menunggu transaksi "
            + owner
            + " commit atau abort pada "
            + request.resource());
  }

  private AccessDecision grantAfterWait(AccessRequest request) {
    TransactionId tx = request.tx();
    long ts = registry.get(tx).timestamp();
    var resource = request.resource();
    switch (request.mode()) {
      case READ -> {
        if (timestamps.writeTs(resource) > ts) {
          return AccessDecision.aborted(writeTsReason(resource, tx, timestamps.writeTs(resource)));
        }
        timestamps.markRead(resource, ts);
      }
      case WRITE -> {
        if (timestamps.readTs(resource) > ts) {
          return AccessDecision.aborted(readTsReason(resource, tx));
        }
        if (timestamps.writeTs(resource) > ts) {
          return AccessDecision.aborted(writeTsReason(resource, tx, timestamps.writeTs(resource)));
        }
        timestamps.markWrite(resource, ts);
        intents.holdUntilFinal(tx, resource);
      }
    }
    return AccessDecision.granted();
  }

  private static String readTsReason(Object resource, TransactionId tx) {
    return "Transaksi "
        + tx
        + " ditolak: timestamp lebih tua dari akses yang lebih baru pada "
        + resource;
  }

  private static String writeTsReason(Object resource, TransactionId tx, long writeTs) {
    return "Transaksi "
        + tx
        + " ditolak: transaksi dengan timestamp "
        + writeTs
        + " sudah menulis "
        + resource;
  }
}
