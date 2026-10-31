package dynamitedb.ccm.protocol;

import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class LockState {
  public Set<TransactionId> sharedOwners = Set.of();
  public Optional<TransactionId> exclusiveOwner = Optional.empty();
  public List<WaitToken> waitQueue = List.of();
}
