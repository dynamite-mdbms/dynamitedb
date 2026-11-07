package dynamitedb.ccm.timestamp;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.Objects;

public class TimestampOrderingProtocol implements ConcurrencyProtocol {
  private final TimestampRegistry timestamps;
  private final StrictWriteIntentTable intents;
  private final TransactionRegistry registry;

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
        if (intents.conflictingOwner(resource, tx).isPresent()) {
          return AccessDecision.aborted(intentReason(resource, tx));
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

        if (intents.conflictingOwner(resource, tx).isPresent()) {
          return AccessDecision.aborted(intentReason(resource, tx));
        }
        timestamps.markWrite(resource, ts);
        intents.holdUntilFinal(tx, resource);
      }
    }
    return AccessDecision.granted();
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    Objects.requireNonNull(ticket, "ticket");
    throw new DbmsException(
        ErrorCode.INVALID_STATE,
        "Protokol timestamp tidak menunggu; tidak ada ticket untuk diakses");
  }

  @Override
  public synchronized void onFinish(TransactionId tx) {
    intents.release(tx);
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
