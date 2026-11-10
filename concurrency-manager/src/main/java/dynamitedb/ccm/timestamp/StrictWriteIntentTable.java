package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class StrictWriteIntentTable {
  private final Map<ResourceId, TransactionId> intents = new HashMap<>();

  public void holdUntilFinal(TransactionId tx, ResourceId resource) {
    intents.put(resource, tx);
  }

  public Optional<TransactionId> conflictingOwner(ResourceId resource, TransactionId requester) {
    TransactionId owner = intents.get(resource);
    if (owner == null || owner.equals(requester)) {
      return Optional.empty();
    }
    return Optional.of(owner);
  }

  public void release(TransactionId tx) {
    intents.entrySet().removeIf(entry -> entry.getValue().equals(tx));
  }
}
