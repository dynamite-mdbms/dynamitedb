package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Optional;

public class StrictWriteIntentTable {
  public void holdUntilFinal(TransactionId tx, ResourceId resource) {}

  public Optional<TransactionId> conflictingOwner(ResourceId resource, TransactionId requester) {
    return Optional.empty();
  }

  public void release(TransactionId tx) {}
}
