package dynamitedb.ccm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.ccm.protocol.LockManager;
import dynamitedb.ccm.protocol.Strict2PLProtocol;
import dynamitedb.ccm.recovery.MaintenanceController;
import dynamitedb.ccm.recovery.TransactionRecoveryBridge;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeadlockLifecycleTest {
  private final List<String> events = new ArrayList<>();
  private final TransactionRegistry registry = new TransactionRegistry();
  private final LockManager locks = new LockManager();
  private final FakeRecovery recovery = new FakeRecovery(events);
  private final ConcurrencyManagerImpl ccm =
      new ConcurrencyManagerImpl(
          registry,
          new Strict2PLProtocol(locks),
          new TransactionRecoveryBridge(recovery, recovery),
          new MaintenanceController(registry));

  @Test
  void abortsNewestDeadlockParticipantAndLetsSurvivorCommit() {
    ccm.initializeRecoveredState(
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty()));
    TransactionId first = ccm.begin();
    TransactionId second = ccm.begin();
    assertEquals(AccessState.GRANTED, ccm.requestAccess(write(first, 1)).state());
    assertEquals(AccessState.GRANTED, ccm.requestAccess(write(second, 2)).state());
    var firstWait = ccm.requestAccess(write(first, 2));

    var deadlockingRequest = ccm.requestAccess(write(second, 1));

    assertEquals(AccessState.WAITING, firstWait.state());
    assertEquals(AccessState.ABORTED, deadlockingRequest.state());
    assertEquals(TransactionStatus.ABORTED, ccm.status(second));
    assertEquals(AccessState.GRANTED, ccm.awaitAccess(firstWait.ticket().orElseThrow()).state());
    ccm.commit(first);
    assertEquals(TransactionStatus.COMMITTED, ccm.status(first));
    assertEquals(1, events.stream().filter(event -> event.equals("rollback T2")).count());
  }

  private static AccessRequest write(TransactionId tx, long tableId) {
    return new AccessRequest(tx, ResourceId.table(tableId), AccessMode.WRITE);
  }
}
