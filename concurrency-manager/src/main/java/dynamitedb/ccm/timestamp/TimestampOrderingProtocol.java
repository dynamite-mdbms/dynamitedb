package dynamitedb.ccm.timestamp;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;

public class TimestampOrderingProtocol implements ConcurrencyProtocol {
  private final TimestampRegistry timestamps;
  private final StrictWriteIntentTable intents;
  private final TransactionRegistry registry;

  public TimestampOrderingProtocol(
      TimestampRegistry timestamps, StrictWriteIntentTable intents, TransactionRegistry registry) {
    this.timestamps = timestamps;
    this.intents = intents;
    this.registry = registry;
  }

  @Override
  public AccessDecision request(AccessRequest request) {
    return AccessDecision.granted();
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    return AccessDecision.granted();
  }

  @Override
  public void onFinish(TransactionId tx) {
    intents.release(tx);
  }
}
