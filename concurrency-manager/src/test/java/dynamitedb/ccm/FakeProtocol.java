package dynamitedb.ccm;

import dynamitedb.ccm.protocol.ConcurrencyProtocol;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

final class FakeProtocol implements ConcurrencyProtocol {
  private final String name;
  private final List<String> events;
  private volatile Function<AccessRequest, AccessDecision> onRequest =
      request -> AccessDecision.granted();
  private volatile AccessDecision onAwait = AccessDecision.granted();

  FakeProtocol(String name, List<String> events) {
    this.name = name;
    this.events = events;
  }

  void answerRequestsWith(Function<AccessRequest, AccessDecision> answer) {
    onRequest = answer;
  }

  void answerWaitsWith(AccessDecision answer) {
    onAwait = answer;
  }

  static AccessDecision waitFor(TransactionId tx) {
    return AccessDecision.waiting(new WaitToken(tx, UUID.randomUUID()), "menunggu lock");
  }

  @Override
  public AccessDecision request(AccessRequest request) {
    events.add(name + " request " + request.tx());
    return onRequest.apply(request);
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    events.add(name + " await " + ticket.tx());
    return onAwait;
  }

  @Override
  public void onFinish(TransactionId tx) {
    events.add(name + " finish " + tx);
  }
}
