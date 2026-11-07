package dynamitedb.ccm.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LockStateTest {
  @Test
  void ownerAndQueueCollectionsAreIndependentAndMutable() {
    var first = new LockState();
    var second = new LockState();
    var owner = new TransactionId(1);
    var token = new WaitToken(owner, UUID.randomUUID());
    first.sharedOwners.add(owner);
    first.waitQueue.add(token);
    assertNotSame(first.sharedOwners, second.sharedOwners);
    assertNotSame(first.waitQueue, second.waitQueue);
    assertEquals(0, second.sharedOwners.size());
    assertEquals(0, second.waitQueue.size());
  }
}
