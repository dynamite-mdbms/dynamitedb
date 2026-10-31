package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Optional;

public abstract class StrictWriteIntentTable {
  public abstract void holdUntilFinal(TransactionId tx, ResourceId resource);

  public abstract Optional<TransactionId> conflictingOwner(
      ResourceId resource, TransactionId requester);

  public abstract void release(TransactionId tx);
}
