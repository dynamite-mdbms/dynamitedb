package dynamitedb.ccm.timestamp;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;

public abstract class TimestampOrderingProtocol implements ConcurrencyProtocol {
  private TimestampRegistry timestamps;
  private StrictWriteIntentTable intents;
  private TransactionRegistry registry;

  @Override
  public abstract AccessDecision request(AccessRequest request);

  @Override
  public abstract AccessDecision awaitAccess(WaitToken ticket);

  @Override
  public abstract void onFinish(TransactionId tx);
}
