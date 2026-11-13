package dynamitedb.ccm.timestamp;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class TimestampOrderingProtocol implements ConcurrencyProtocol {
  private final TimestampRegistry timestamps;
  private final StrictWriteIntentTable intents;
  private final TransactionRegistry registry;
  private final Map<WaitToken, Pending> pending = new HashMap<>();

  public TimestampOrderingProtocol(
      TimestampRegistry timestamps, StrictWriteIntentTable intents, TransactionRegistry registry) {
    this.timestamps = Objects.requireNonNull(timestamps, "timestamps");
    this.intents = Objects.requireNonNull(intents, "intents");
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public synchronized AccessDecision request(AccessRequest request) {
    Objects.requireNonNull(request, "request");
    for (var entry : pending.entrySet()) {
      AccessRequest previous = entry.getValue().request;
      if (previous.tx().equals(request.tx()) && previous.resource().equals(request.resource())) {
        if (!previous.mode().equals(request.mode())) {
          throw new DbmsException(ErrorCode.INVALID_STATE, "Transaksi sudah menunggu akses lain");
        }
        return AccessDecision.waiting(entry.getKey(), "waiting for older writer");
      }
    }
    return decide(request, null);
  }

  private AccessDecision decide(AccessRequest request, WaitToken ticket) {
    TransactionId tx = request.tx();
    long ts = registry.get(tx).timestamp();
    var resource = request.resource();
    switch (request.mode()) {
      case READ -> {
        if (timestamps.writeTs(resource) > ts) {
          return AccessDecision.aborted(writeTsReason(resource, tx, timestamps.writeTs(resource)));
        }
        var owner = intents.conflictingOwner(resource, tx);
        if (owner.isPresent()) {
          return conflict(request, owner.orElseThrow(), ts, ticket);
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

        var owner = intents.conflictingOwner(resource, tx);
        if (owner.isPresent()) {
          return conflict(request, owner.orElseThrow(), ts, ticket);
        }
        timestamps.markWrite(resource, ts);
        intents.holdUntilFinal(tx, resource);
      }
    }
    return AccessDecision.granted();
  }

  private AccessDecision conflict(
      AccessRequest request, TransactionId owner, long timestamp, WaitToken ticket) {
    if (registry.get(owner).timestamp() > timestamp) {
      return AccessDecision.aborted(intentReason(request.resource(), request.tx()));
    }
    if (ticket == null) {
      ticket = new WaitToken(request.tx(), UUID.randomUUID());
      pending.put(ticket, new Pending(request));
    }
    return AccessDecision.waiting(ticket, "waiting for older writer");
  }

  @Override
  public synchronized AccessDecision awaitAccess(WaitToken ticket) {
    Objects.requireNonNull(ticket, "ticket");
    Pending entry = pending.get(ticket);
    if (entry == null || entry.claimed) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Ticket timestamp tidak dikenal atau sudah dipakai");
    }
    entry.claimed = true;
    try {
      while (pending.containsKey(ticket)) {
        if (Thread.currentThread().isInterrupted()) {
          return AccessDecision.aborted("waiting for older writer interrupted");
        }
        AccessDecision decision = decide(entry.request, ticket);
        if (decision.state() != AccessState.WAITING) {
          return decision;
        }
        try {
          wait();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          return AccessDecision.aborted("waiting for older writer interrupted");
        }
      }
      return AccessDecision.aborted("request cancelled");
    } finally {
      pending.remove(ticket);
    }
  }

  @Override
  public synchronized void onFinish(TransactionId tx) {
    intents.release(tx);
    pending.entrySet().removeIf(entry -> entry.getKey().tx().equals(tx));
    notifyAll();
  }

  private static final class Pending {
    private final AccessRequest request;
    private boolean claimed;

    private Pending(AccessRequest request) {
      this.request = request;
    }
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

  private static String intentReason(Object resource, TransactionId tx) {
    return "Transaksi "
        + tx
        + " ditolak: transaksi lain memegang penulisan belum final pada "
        + resource;
  }
}
